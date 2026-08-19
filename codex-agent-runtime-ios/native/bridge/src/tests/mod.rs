use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::slice;
use std::str;

use codex_app_server_protocol::ClientNotification;
use codex_app_server_protocol::ClientRequest;
use codex_exec_server::EnvironmentManager;
use serde_json::Value;
use serde_json::json;
use tempfile::TempDir;
use toml::Value as TomlValue;

use crate::CodexAgentIosBuffer;
use crate::codex_agent_ios_buffer_free;
use crate::config::CodexHomeLease;
use crate::config::RuntimeConfiguration;
use crate::config::RuntimePaths;
use crate::protocol::sanitize_request;
use crate::protocol::unsupported_client_capability;
use crate::runtime::safe_config_overrides;
use crate::runtime::start_app_server;
use crate::workspace::execute_workspace_tool;
use crate::workspace_read::SEARCH_LIMITS;
use crate::workspace_read::SearchLimits;
use crate::workspace_read::search_text_with_limits;
use crate::write_buffer;

    fn workspace() -> (TempDir, PathBuf) {
        let sandbox = TempDir::new().expect("sandbox");
        let workspace = sandbox.path().join("workspace");
        fs::create_dir(&workspace).expect("workspace");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        (sandbox, workspace)
    }

    fn configuration(sandbox: &Path, workspace: &Path, codex_home: &Path) -> RuntimeConfiguration {
        RuntimeConfiguration {
            sandbox_root_path: sandbox.to_path_buf(),
            workspace_path: workspace.to_path_buf(),
            codex_home_path: codex_home.to_path_buf(),
            security_scoped_workspace: false,
        }
    }

mod config;
mod ffi_runtime;
mod protocol;
mod workspace;
