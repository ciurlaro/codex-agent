use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::Ordering;

use codex_app_server::in_process::InProcessServerEvent;
use codex_app_server_client::InProcessAppServerClient;
use codex_app_server_protocol::JSONRPCErrorError;
use serde::Serialize;
use serde_json::Value;
use serde_json::json;
use tokio::sync::mpsc;

use crate::BridgeEvent;
use crate::display_error;

const UNSUPPORTED_CAPABILITY_CODE: i64 = -32004;
const IOS_DEVELOPER_INSTRUCTIONS: &str = "Answer conversationally using Markdown. This iOS runtime has no shell, process, Git, plugin, hook, or MCP tools. Use only the advertised local filesystem tools and apply_patch inside the selected workspace.";
const ALLOWED_DYNAMIC_TOOLS: [&str; 5] = [
    "apply_patch",
    "read_file",
    "list_directory",
    "search_text",
    "write_file",
];

pub(crate) async fn handle_server_event(
    client: &InProcessAppServerClient,
    event: InProcessServerEvent,
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
) {
    match event {
        InProcessServerEvent::ServerRequest(request) => {
            let value = match serde_json::to_value(&request) {
                Ok(value) => value,
                Err(error) => {
                    send_event(event_tx, closing, 2, display_error(error)).await;
                    return;
                }
            };
            let method = value
                .get("method")
                .and_then(Value::as_str)
                .unwrap_or_default();
            if let Some(capability) = unsupported_server_capability(method) {
                if let Err(error) = client
                    .reject_server_request(request.id().clone(), unsupported_error(capability))
                    .await
                {
                    send_event(event_tx, closing, 2, display_error(error)).await;
                }
            } else if let Ok(message) = serde_json::to_string(&value) {
                send_event(event_tx, closing, 1, message).await;
            }
        }
        InProcessServerEvent::ServerNotification(notification) => {
            if let Ok(message) = serde_json::to_string(&notification) {
                send_event(event_tx, closing, 1, message).await;
            }
        }
        InProcessServerEvent::Lagged { skipped } => {
            send_event(
                event_tx,
                closing,
                2,
                format!("embedded App Server event queue dropped {skipped} event(s)"),
            )
            .await;
        }
    }
}

pub(crate) fn sanitize_request(value: &mut Value, method: &str, workspace: &Path) -> Result<(), String> {
    if !matches!(method, "thread/start" | "thread/resume" | "turn/start") {
        return Ok(());
    }
    let params = value
        .get_mut("params")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| format!("{method} requires object params"))?;
    params.insert(
        "cwd".to_string(),
        Value::String(workspace.to_string_lossy().into_owned()),
    );
    params.insert(
        "runtimeWorkspaceRoots".to_string(),
        json!([workspace.to_string_lossy()]),
    );
    if matches!(method, "thread/start" | "thread/resume") {
        params.insert("sandbox".to_string(), json!("workspace-write"));
        params.remove("permissions");
        params.insert(
            "developerInstructions".to_string(),
            Value::String(IOS_DEVELOPER_INSTRUCTIONS.to_string()),
        );
        params.insert("config".to_string(), safe_thread_config());
    } else {
        params.insert(
            "sandboxPolicy".to_string(),
            json!({
                "type": "workspaceWrite",
                "writableRoots": [],
                "networkAccess": false,
                "excludeTmpdirEnvVar": true,
                "excludeSlashTmp": true
            }),
        );
        params.remove("permissions");
    }
    if method == "thread/start"
        && let Some(tools) = params.get_mut("dynamicTools").and_then(Value::as_array_mut)
    {
        tools.retain(|tool| {
            tool.get("name")
                .and_then(Value::as_str)
                .is_some_and(|name| ALLOWED_DYNAMIC_TOOLS.contains(&name))
        });
    }
    Ok(())
}

fn safe_thread_config() -> Value {
    json!({
        "web_search": "disabled",
        "features": {
            "shell_tool": false,
            "code_mode": false,
            "code_mode_buffered_exec": false,
            "code_mode_host": false,
            "code_mode_only": false,
            "multi_agent": false,
            "apps": false,
            "enable_mcp_apps": false,
            "plugins": false,
            "image_generation": false,
            "goals": false,
            "hooks": false,
            "skill_mcp_dependency_install": false,
            "workspace_dependencies": false,
            "standalone_web_search": false
        }
    })
}

pub(crate) fn unsupported_client_capability(method: &str) -> Option<&'static str> {
    if method.starts_with("command/") || method == "thread/shellCommand" {
        Some("process execution")
    } else if method.starts_with("plugin/")
        || method.starts_with("marketplace/")
        || method.starts_with("app/")
    {
        Some("plugins and apps")
    } else if method.starts_with("mcpServer") || method == "config/mcpServer/reload" {
        Some("MCP")
    } else if method.starts_with("hooks/") {
        Some("process hooks")
    } else if method.starts_with("externalAgentConfig/") {
        Some("external agent import")
    } else if method.starts_with("windowsSandbox/") {
        Some("platform sandbox setup")
    } else if method.starts_with("fs/") || method == "fuzzyFileSearch" {
        Some("unscoped filesystem API")
    } else if matches!(
        method,
        "config/batchWrite"
            | "config/value/write"
            | "experimentalFeature/enablement/set"
            | "skills/extraRoots/set"
    ) {
        Some("runtime capability configuration")
    } else {
        None
    }
}

fn unsupported_server_capability(method: &str) -> Option<&'static str> {
    if method.starts_with("item/commandExecution/") {
        Some("process execution")
    } else if method.starts_with("mcpServer/") {
        Some("MCP")
    } else {
        None
    }
}

pub(crate) fn unsupported_error(capability: &str) -> JSONRPCErrorError {
    JSONRPCErrorError {
        code: UNSUPPORTED_CAPABILITY_CODE,
        message: format!("Unsupported iOS capability: {capability}"),
        data: Some(json!({ "capability": capability })),
    }
}

pub(crate) async fn send_json(
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
    value: &impl Serialize,
) -> Result<(), String> {
    let message = serde_json::to_string(value).map_err(display_error)?;
    send_event(event_tx, closing, 1, message).await;
    Ok(())
}

pub(crate) async fn send_event(
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
    kind: i32,
    payload: String,
) {
    if !closing.load(Ordering::Acquire) {
        let _ = event_tx.send(BridgeEvent { kind, payload }).await;
    }
}
