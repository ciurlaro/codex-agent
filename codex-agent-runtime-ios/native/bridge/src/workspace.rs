use std::path::Path;

use serde::Serialize;
use serde_json::Value;

use crate::workspace_patch::apply_workspace_patch;
use crate::workspace_read::list_directory;
use crate::workspace_read::read_file;
use crate::workspace_read::search_text;
use crate::workspace_write::write_file;

pub(crate) const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024;

#[derive(Serialize)]
pub(crate) struct WorkspaceToolResult {
    pub(crate) success: bool,
    pub(crate) text: String,
}

pub(crate) fn execute_workspace_tool(
    workspace: &Path,
    tool: &str,
    arguments: Value,
) -> WorkspaceToolResult {
    let result = match tool {
        "apply_patch" => apply_workspace_patch(workspace, arguments),
        "read_file" => read_file(workspace, arguments),
        "list_directory" => list_directory(workspace, arguments),
        "search_text" => search_text(workspace, arguments),
        "write_file" => write_file(workspace, arguments),
        _ => Err(format!("Unsupported iOS workspace tool: {tool}")),
    };
    match result {
        Ok(text) => WorkspaceToolResult {
            success: true,
            text,
        },
        Err(text) => WorkspaceToolResult {
            success: false,
            text,
        },
    }
}
