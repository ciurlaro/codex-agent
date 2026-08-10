use std::fs;
use std::fs::OpenOptions;
use std::io;
use std::io::Write;
use std::path::Path;
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;

use serde::Deserialize;
use serde_json::Value;

use crate::display_error;
use crate::workspace::MAX_FILE_BYTES;

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Deserialize)]
struct WriteFileArguments {
    path: String,
    content: String,
}

pub(crate) fn write_file(workspace: &Path, arguments: Value) -> Result<String, String> {
    let arguments: WriteFileArguments = serde_json::from_value(arguments).map_err(display_error)?;
    if arguments.content.len() as u64 > MAX_FILE_BYTES {
        return Err("write_file content exceeds 4 MiB".to_string());
    }
    let path = resolve_for_write(workspace, &arguments.path)?;
    atomic_write(&path, arguments.content.as_bytes()).map_err(display_error)?;
    Ok(format!(
        "Wrote {} bytes to {}",
        arguments.content.len(),
        arguments.path
    ))
}

pub(crate) fn atomic_write(path: &Path, contents: &[u8]) -> io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no parent"))?;
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "non-UTF-8 file name"))?;
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let temporary = parent.join(format!(
        ".{name}.codex-ios-{}-{sequence}.tmp",
        std::process::id()
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)?;
        file.write_all(contents)?;
        file.sync_all()?;
        if let Ok(metadata) = path.metadata() {
            fs::set_permissions(&temporary, metadata.permissions())?;
        }
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

pub(crate) fn resolve_existing(workspace: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = validated_relative_path(relative)?;
    resolve_existing_path(workspace, relative).map_err(display_error)
}

pub(crate) fn resolve_for_write(workspace: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = validated_relative_path(relative)?;
    resolve_for_write_path(workspace, relative).map_err(display_error)
}

pub(crate) fn resolve_existing_path(workspace: &Path, relative: &Path) -> io::Result<PathBuf> {
    validate_workspace_relative_path(relative)?;
    reject_symlink_components(workspace, relative)?;
    let path = workspace.join(relative).canonicalize()?;
    if !path.starts_with(workspace) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "workspace path escapes through a symlink",
        ));
    }
    Ok(path)
}

pub(crate) fn resolve_for_write_path(workspace: &Path, relative: &Path) -> io::Result<PathBuf> {
    validate_workspace_relative_path(relative)?;
    reject_symlink_components(workspace, relative)?;
    let joined = workspace.join(relative);
    let parent = joined
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no parent"))?
        .canonicalize()?;
    if !parent.starts_with(workspace) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "workspace path escapes through a symlink",
        ));
    }
    if joined.exists() {
        let existing = joined.canonicalize()?;
        if !existing.starts_with(workspace) {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "workspace path escapes through a symlink",
            ));
        }
    }
    Ok(parent.join(
        joined
            .file_name()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no file name"))?,
    ))
}

fn validated_relative_path(value: &str) -> Result<&Path, String> {
    let path = Path::new(value);
    validate_workspace_relative_path(path).map_err(display_error)?;
    Ok(path)
}

pub(crate) fn validate_workspace_relative_path(path: &Path) -> io::Result<()> {
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                std::path::Component::ParentDir | std::path::Component::Prefix(_)
            )
        })
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "workspace path must be relative and must not contain '..'",
        ));
    }
    Ok(())
}

pub(crate) fn reject_symlink_components(workspace: &Path, relative: &Path) -> io::Result<()> {
    let mut current = workspace.to_path_buf();
    for component in relative.components() {
        if matches!(component, std::path::Component::CurDir) {
            continue;
        }
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "workspace path contains a symlink",
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => break,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}
