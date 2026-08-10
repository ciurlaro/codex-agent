use std::fs;
use std::io::Read;
use std::path::Path;
use std::str;

use serde::Deserialize;
use serde_json::Value;

use crate::display_error;
use crate::workspace::MAX_FILE_BYTES;
use crate::workspace_write::resolve_existing;

const MAX_READ_BYTES: usize = 256 * 1024;
const MAX_DIRECTORY_ENTRIES: usize = 1_000;
const MAX_SEARCH_RESULTS: usize = 200;
const MAX_SEARCH_OUTPUT_BYTES: usize = 256 * 1024;
const MAX_SEARCH_FILES: usize = 10_000;
const MAX_SEARCH_DIRECTORIES: usize = 1_000;
const MAX_SEARCH_DEPTH: usize = 32;
const MAX_SEARCH_SCANNED_BYTES: u64 = 64 * 1024 * 1024;

#[derive(Deserialize)]
struct ReadFileArguments {
    path: String,
    #[serde(default)]
    offset: usize,
    limit: Option<usize>,
}

pub(crate) fn read_file(workspace: &Path, arguments: Value) -> Result<String, String> {
    let arguments: ReadFileArguments = serde_json::from_value(arguments).map_err(display_error)?;
    let path = resolve_existing(workspace, &arguments.path)?;
    let metadata = path.metadata().map_err(display_error)?;
    if !metadata.is_file() || metadata.len() > MAX_FILE_BYTES {
        return Err("read_file requires a UTF-8 file no larger than 4 MiB".to_string());
    }
    let text = fs::read_to_string(path).map_err(display_error)?;
    if arguments.offset > text.len() || !text.is_char_boundary(arguments.offset) {
        return Err("read_file offset is outside a UTF-8 character boundary".to_string());
    }
    let limit = arguments
        .limit
        .unwrap_or(MAX_READ_BYTES)
        .min(MAX_READ_BYTES);
    let mut end = (arguments.offset + limit).min(text.len());
    while end > arguments.offset && !text.is_char_boundary(end) {
        end -= 1;
    }
    Ok(text[arguments.offset..end].to_string())
}

#[derive(Deserialize)]
struct PathArguments {
    #[serde(default = "default_relative_path")]
    path: String,
}

fn default_relative_path() -> String {
    ".".to_string()
}

pub(crate) fn list_directory(workspace: &Path, arguments: Value) -> Result<String, String> {
    let arguments: PathArguments = serde_json::from_value(arguments).map_err(display_error)?;
    let path = resolve_existing(workspace, &arguments.path)?;
    if !path.is_dir() {
        return Err("list_directory path is not a directory".to_string());
    }
    let mut entries = path
        .read_dir()
        .map_err(display_error)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(display_error)?;
    entries.sort_by_key(|entry| entry.file_name());
    if entries.len() > MAX_DIRECTORY_ENTRIES {
        return Err("list_directory contains more than 1000 entries".to_string());
    }
    entries
        .into_iter()
        .map(|entry| {
            let metadata = fs::symlink_metadata(entry.path()).map_err(display_error)?;
            let kind = if metadata.file_type().is_symlink() {
                "symlink"
            } else if metadata.is_dir() {
                "directory"
            } else {
                "file"
            };
            Ok(format!("{kind}\t{}", entry.file_name().to_string_lossy()))
        })
        .collect::<Result<Vec<_>, String>>()
        .map(|lines| lines.join("\n"))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SearchTextArguments {
    query: String,
    #[serde(default = "default_relative_path")]
    path: String,
    #[serde(default)]
    case_sensitive: bool,
}

#[derive(Clone, Copy)]
pub(crate) struct SearchLimits {
    pub(crate) files: usize,
    pub(crate) directories: usize,
    pub(crate) depth: usize,
    pub(crate) scanned_bytes: u64,
    pub(crate) results: usize,
    pub(crate) output_bytes: usize,
}

pub(crate) const SEARCH_LIMITS: SearchLimits = SearchLimits {
    files: MAX_SEARCH_FILES,
    directories: MAX_SEARCH_DIRECTORIES,
    depth: MAX_SEARCH_DEPTH,
    scanned_bytes: MAX_SEARCH_SCANNED_BYTES,
    results: MAX_SEARCH_RESULTS,
    output_bytes: MAX_SEARCH_OUTPUT_BYTES,
};

#[derive(Default)]
struct SearchState {
    pub(crate) files: usize,
    pub(crate) directories: usize,
    pub(crate) scanned_bytes: u64,
    pub(crate) output_bytes: usize,
    truncation: Option<&'static str>,
    stopped: bool,
}

impl SearchState {
    fn truncate(&mut self, budget: &'static str, stop: bool) {
        if self.truncation.is_none() || stop {
            self.truncation = Some(budget);
        }
        self.stopped |= stop;
    }
}

pub(crate) fn search_text(workspace: &Path, arguments: Value) -> Result<String, String> {
    search_text_with_limits(workspace, arguments, SEARCH_LIMITS)
}

pub(crate) fn search_text_with_limits(
    workspace: &Path,
    arguments: Value,
    limits: SearchLimits,
) -> Result<String, String> {
    let arguments: SearchTextArguments =
        serde_json::from_value(arguments).map_err(display_error)?;
    if arguments.query.is_empty() {
        return Err("search_text query must not be empty".to_string());
    }
    let root = resolve_existing(workspace, &arguments.path)?;
    if !root.is_dir() {
        return Err("search_text path is not a directory".to_string());
    }
    let needle = if arguments.case_sensitive {
        arguments.query
    } else {
        arguments.query.to_lowercase()
    };
    let mut results = Vec::new();
    let mut state = SearchState::default();
    search_directory(
        workspace,
        &root,
        &needle,
        arguments.case_sensitive,
        /*depth*/ 0,
        limits,
        &mut state,
        &mut results,
    )?;
    let mut output = if results.is_empty() {
        "No matches.".to_string()
    } else {
        results.join("\n")
    };
    if let Some(budget) = state.truncation {
        output.push_str(&format!(
            "\n[search_text truncated: {budget} budget reached after {} files, {} directories, and {} bytes scanned]",
            state.files, state.directories, state.scanned_bytes
        ));
    }
    Ok(output)
}

fn search_directory(
    workspace: &Path,
    directory: &Path,
    needle: &str,
    case_sensitive: bool,
    depth: usize,
    limits: SearchLimits,
    state: &mut SearchState,
    results: &mut Vec<String>,
) -> Result<(), String> {
    if state.stopped {
        return Ok(());
    }
    if state.directories >= limits.directories {
        state.truncate("visited directories", true);
        return Ok(());
    }
    state.directories += 1;
    for entry in directory.read_dir().map_err(display_error)? {
        if state.stopped {
            break;
        }
        let entry = entry.map_err(display_error)?;
        let metadata = fs::symlink_metadata(entry.path()).map_err(display_error)?;
        let path = entry.path();
        if metadata.is_dir() {
            if depth >= limits.depth {
                if state.directories >= limits.directories {
                    state.truncate("visited directories", true);
                    break;
                }
                state.directories += 1;
                state.truncate("recursion depth", false);
            } else {
                search_directory(
                    workspace,
                    &path,
                    needle,
                    case_sensitive,
                    depth + 1,
                    limits,
                    state,
                    results,
                )?;
            }
            continue;
        }
        if state.files >= limits.files {
            state.truncate("visited files", true);
            break;
        }
        state.files += 1;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            continue;
        }
        if metadata.len() > MAX_FILE_BYTES {
            continue;
        }
        let remaining_bytes = limits.scanned_bytes.saturating_sub(state.scanned_bytes);
        if metadata.len() > remaining_bytes {
            state.truncate("scanned bytes", true);
            break;
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        fs::File::open(&path)
            .map_err(display_error)?
            .take(remaining_bytes + 1)
            .read_to_end(&mut bytes)
            .map_err(display_error)?;
        if bytes.len() as u64 > remaining_bytes {
            state.scanned_bytes = limits.scanned_bytes;
            state.truncate("scanned bytes", true);
            break;
        }
        state.scanned_bytes += bytes.len() as u64;
        if bytes.contains(&0) {
            continue;
        }
        let Ok(text) = str::from_utf8(&bytes) else {
            continue;
        };
        for (index, line) in text.lines().enumerate() {
            let haystack = if case_sensitive {
                line.to_string()
            } else {
                line.to_lowercase()
            };
            if haystack.contains(needle) {
                let relative = path.strip_prefix(workspace).map_err(display_error)?;
                let excerpt: String = line.chars().take(512).collect();
                let match_line = format!("{}:{}:{excerpt}", relative.display(), index + 1);
                let separator_bytes = usize::from(!results.is_empty());
                if state.output_bytes + separator_bytes + match_line.len() > limits.output_bytes {
                    state.truncate("result output", true);
                    break;
                }
                state.output_bytes += separator_bytes + match_line.len();
                results.push(match_line);
                if results.len() >= limits.results {
                    state.truncate("result count", true);
                    break;
                }
            }
        }
    }
    Ok(())
}
