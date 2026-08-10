use std::collections::HashSet;
use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::OnceLock;

use serde::Deserialize;

use crate::display_error;

static ACTIVE_CODEX_HOMES: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RuntimeConfiguration {
    pub(crate) sandbox_root_path: PathBuf,
    pub(crate) workspace_path: PathBuf,
    pub(crate) codex_home_path: PathBuf,
}

#[derive(Clone, Debug)]
pub(crate) struct RuntimePaths {
    pub(crate) workspace: PathBuf,
    pub(crate) codex_home: PathBuf,
}

impl RuntimeConfiguration {
    pub(crate) fn validate(&self) -> Result<RuntimePaths, String> {
        for path in [
            &self.sandbox_root_path,
            &self.workspace_path,
            &self.codex_home_path,
        ] {
            if !path.is_absolute() {
                return Err(format!(
                    "iOS runtime path must be absolute: {}",
                    path.display()
                ));
            }
        }
        let sandbox = self
            .sandbox_root_path
            .canonicalize()
            .map_err(display_error)?;
        if !sandbox.is_dir() {
            return Err("iOS sandbox root must be an existing directory".to_string());
        }
        let workspace = existing_directory_in_sandbox(&sandbox, &self.workspace_path)?;
        if !workspace.is_dir() {
            return Err("iOS workspace must be an existing directory".to_string());
        }
        let prospective_codex_home =
            prospective_directory_in_sandbox(&sandbox, &self.codex_home_path)?;
        ensure_disjoint_runtime_paths(&workspace, &prospective_codex_home)?;
        fs::create_dir_all(&prospective_codex_home).map_err(display_error)?;
        let codex_home = existing_directory_in_sandbox(&sandbox, &prospective_codex_home)?;
        ensure_disjoint_runtime_paths(&workspace, &codex_home)?;
        Ok(RuntimePaths {
            workspace,
            codex_home,
        })
    }
}

fn existing_directory_in_sandbox(sandbox: &Path, path: &Path) -> Result<PathBuf, String> {
    let canonical = path.canonicalize().map_err(display_error)?;
    if !canonical.starts_with(sandbox) {
        return Err(format!(
            "iOS runtime path escapes the application sandbox: {}",
            canonical.display()
        ));
    }
    Ok(canonical)
}

fn prospective_directory_in_sandbox(sandbox: &Path, path: &Path) -> Result<PathBuf, String> {
    let mut existing_ancestor = path;
    while !existing_ancestor.exists() {
        existing_ancestor = existing_ancestor
            .parent()
            .ok_or_else(|| "iOS runtime path has no existing ancestor".to_string())?;
    }
    let mut prospective = existing_directory_in_sandbox(sandbox, existing_ancestor)?;
    let missing = path.strip_prefix(existing_ancestor).map_err(display_error)?;
    for component in missing.components() {
        match component {
            std::path::Component::Normal(component) => prospective.push(component),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                prospective.pop();
            }
            std::path::Component::RootDir | std::path::Component::Prefix(_) => {
                return Err("invalid iOS runtime path".to_string());
            }
        }
    }
    if !prospective.starts_with(sandbox) {
        return Err(format!(
            "iOS runtime path escapes the application sandbox: {}",
            prospective.display()
        ));
    }
    Ok(prospective)
}

fn ensure_disjoint_runtime_paths(workspace: &Path, codex_home: &Path) -> Result<(), String> {
    if workspace == codex_home
        || workspace.starts_with(codex_home)
        || codex_home.starts_with(workspace)
    {
        return Err("iOS workspace and Codex home must be disjoint directories".to_string());
    }
    Ok(())
}

pub(crate) struct CodexHomeLease {
    path: PathBuf,
}

impl CodexHomeLease {
    pub(crate) fn acquire(path: &Path) -> Result<Self, String> {
        let mut active = ACTIVE_CODEX_HOMES
            .get_or_init(|| Mutex::new(HashSet::new()))
            .lock()
            .map_err(|_| "iOS Codex home registry lock is poisoned".to_string())?;
        if !active.insert(path.to_path_buf()) {
            return Err(format!(
                "An iOS Codex runtime already owns this Codex home: {}",
                path.display()
            ));
        }
        Ok(Self {
            path: path.to_path_buf(),
        })
    }
}

impl Drop for CodexHomeLease {
    fn drop(&mut self) {
        if let Ok(mut active) = ACTIVE_CODEX_HOMES
            .get_or_init(|| Mutex::new(HashSet::new()))
            .lock()
        {
            active.remove(&self.path);
        }
    }
}
