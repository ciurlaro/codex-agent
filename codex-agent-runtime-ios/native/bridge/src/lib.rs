use std::fs;
use std::fs::OpenOptions;
use std::io;
use std::io::Read;
use std::io::Write;
use std::path::Path;
use std::path::PathBuf;
use std::ptr;
use std::slice;
use std::str;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;
use std::thread::JoinHandle;

use codex_app_server::in_process::InProcessServerEvent;
use codex_app_server_client::InProcessAppServerClient;
use codex_app_server_client::InProcessClientStartArgs;
use codex_app_server_protocol::ClientNotification;
use codex_app_server_protocol::ClientRequest;
use codex_app_server_protocol::JSONRPCError;
use codex_app_server_protocol::JSONRPCErrorError;
use codex_app_server_protocol::JSONRPCResponse;
use codex_app_server_protocol::RequestId;
use codex_arg0::Arg0DispatchPaths;
use codex_config::CloudConfigBundleLoader;
use codex_config::LoaderOverrides;
use codex_core::config::ConfigBuilder;
use codex_core::init_state_db;
use codex_exec_server::CopyOptions;
use codex_exec_server::CreateDirectoryOptions;
use codex_exec_server::EnvironmentManager;
use codex_exec_server::ExecutorFileSystem;
use codex_exec_server::ExecutorFileSystemFuture;
use codex_exec_server::FileMetadata;
use codex_exec_server::FileSystemReadStream;
use codex_exec_server::FileSystemSandboxContext;
use codex_exec_server::LocalFileSystem;
use codex_exec_server::ReadDirectoryEntry;
use codex_exec_server::RemoveOptions;
use codex_feedback::CodexFeedback;
use codex_protocol::protocol::SessionSource;
use codex_utils_path_uri::PathUri;
use serde::Deserialize;
use serde::Serialize;
use serde_json::Value;
use serde_json::json;
use tokio::sync::mpsc;
use toml::Value as TomlValue;

const QUEUE_CAPACITY: usize = 64;
const UNSUPPORTED_CAPABILITY_CODE: i64 = -32004;
const MAX_READ_BYTES: usize = 256 * 1024;
const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024;
const MAX_DIRECTORY_ENTRIES: usize = 1_000;
const MAX_SEARCH_RESULTS: usize = 200;
const MAX_SEARCH_OUTPUT_BYTES: usize = 256 * 1024;
const MAX_SEARCH_FILES: usize = 10_000;
const MAX_SEARCH_DIRECTORIES: usize = 1_000;
const MAX_SEARCH_DEPTH: usize = 32;
const MAX_SEARCH_SCANNED_BYTES: u64 = 64 * 1024 * 1024;
const MAX_PATCH_BYTES: usize = 1024 * 1024;
const IOS_DEVELOPER_INSTRUCTIONS: &str = "Answer conversationally using Markdown. This iOS runtime has no shell, process, Git, plugin, hook, or MCP tools. Use only the advertised local filesystem tools and apply_patch inside the selected workspace.";
const ALLOWED_DYNAMIC_TOOLS: [&str; 5] = [
    "apply_patch",
    "read_file",
    "list_directory",
    "search_text",
    "write_file",
];

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeConfiguration {
    sandbox_root_path: PathBuf,
    workspace_path: PathBuf,
    codex_home_path: PathBuf,
}

#[derive(Clone, Debug)]
struct RuntimePaths {
    workspace: PathBuf,
    codex_home: PathBuf,
}

impl RuntimeConfiguration {
    fn validate(&self) -> Result<RuntimePaths, String> {
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

enum BridgeCommand {
    Message(Vec<u8>),
    Shutdown,
}

struct BridgeEvent {
    kind: i32,
    payload: String,
}

#[repr(C)]
pub struct CodexAgentIosBuffer {
    pub data: *mut u8,
    pub length: usize,
}

impl Default for CodexAgentIosBuffer {
    fn default() -> Self {
        Self {
            data: ptr::null_mut(),
            length: 0,
        }
    }
}

#[repr(C)]
pub struct CodexAgentIosRuntime {
    command_tx: mpsc::Sender<BridgeCommand>,
    event_rx: Mutex<mpsc::Receiver<BridgeEvent>>,
    worker: Mutex<Option<JoinHandle<()>>>,
    closing: Arc<AtomicBool>,
}

#[derive(Serialize)]
struct WorkspaceToolResult {
    success: bool,
    text: String,
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_runtime_start(
    configuration: *const u8,
    configuration_length: usize,
    runtime: *mut *mut CodexAgentIosRuntime,
    error: *mut CodexAgentIosBuffer,
) -> i32 {
    ffi_boundary(error, || {
        if runtime.is_null() {
            return Err("runtime output pointer is null".to_string());
        }
        let configuration = read_utf8(configuration, configuration_length)?;
        let configuration: RuntimeConfiguration =
            serde_json::from_str(configuration).map_err(display_error)?;
        let paths = configuration.validate()?;
        let native = start_runtime(paths)?;
        unsafe { runtime.write(Box::into_raw(Box::new(native))) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_runtime_send(
    runtime: *mut CodexAgentIosRuntime,
    message: *const u8,
    message_length: usize,
    error: *mut CodexAgentIosBuffer,
) -> i32 {
    ffi_boundary(error, || {
        let runtime = runtime_ref(runtime)?;
        if runtime.closing.load(Ordering::Acquire) {
            return Err("iOS runtime is closed".to_string());
        }
        let message = read_bytes(message, message_length)?.to_vec();
        runtime
            .command_tx
            .blocking_send(BridgeCommand::Message(message))
            .map_err(|_| "iOS runtime command queue is closed".to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_runtime_receive(
    runtime: *mut CodexAgentIosRuntime,
    kind: *mut i32,
    payload: *mut CodexAgentIosBuffer,
    error: *mut CodexAgentIosBuffer,
) -> i32 {
    let result = std::panic::catch_unwind(|| -> Result<i32, String> {
        if kind.is_null() || payload.is_null() {
            return Err("event output pointer is null".to_string());
        }
        let runtime = runtime_ref(runtime)?;
        let mut receiver = runtime
            .event_rx
            .lock()
            .map_err(|_| "iOS runtime event queue lock is poisoned".to_string())?;
        let Some(event) = receiver.blocking_recv() else {
            return Ok(1);
        };
        unsafe { kind.write(event.kind) };
        write_buffer(payload, event.payload)?;
        Ok(0)
    });
    match result {
        Ok(Ok(status)) => status,
        Ok(Err(message)) => {
            let _ = write_buffer(error, message);
            -1
        }
        Err(_) => {
            let _ = write_buffer(
                error,
                "panic across iOS runtime receive boundary".to_string(),
            );
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_runtime_shutdown(
    runtime: *mut CodexAgentIosRuntime,
    error: *mut CodexAgentIosBuffer,
) -> i32 {
    ffi_boundary(error, || shutdown_runtime(runtime_ref(runtime)?))
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_runtime_destroy(runtime: *mut CodexAgentIosRuntime) {
    if runtime.is_null() {
        return;
    }
    let mut runtime = unsafe { Box::from_raw(runtime) };
    let _ = shutdown_runtime(&mut runtime);
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_workspace_execute(
    configuration: *const u8,
    configuration_length: usize,
    tool: *const u8,
    tool_length: usize,
    arguments: *const u8,
    arguments_length: usize,
    result: *mut CodexAgentIosBuffer,
    error: *mut CodexAgentIosBuffer,
) -> i32 {
    ffi_boundary(error, || {
        let configuration: RuntimeConfiguration =
            serde_json::from_str(read_utf8(configuration, configuration_length)?)
                .map_err(display_error)?;
        let paths = configuration.validate()?;
        let tool = read_utf8(tool, tool_length)?;
        let arguments: Value =
            serde_json::from_str(read_utf8(arguments, arguments_length)?).map_err(display_error)?;
        let response = execute_workspace_tool(&paths.workspace, tool, arguments);
        write_buffer(
            result,
            serde_json::to_string(&response).map_err(display_error)?,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn codex_agent_ios_buffer_free(buffer: *mut CodexAgentIosBuffer) {
    if buffer.is_null() {
        return;
    }
    let buffer = unsafe { &mut *buffer };
    if !buffer.data.is_null() && buffer.length > 0 {
        let slice = ptr::slice_from_raw_parts_mut(buffer.data, buffer.length);
        drop(unsafe { Box::from_raw(slice) });
    }
    *buffer = CodexAgentIosBuffer::default();
}

fn ffi_boundary(
    error: *mut CodexAgentIosBuffer,
    operation: impl FnOnce() -> Result<(), String> + std::panic::UnwindSafe,
) -> i32 {
    match std::panic::catch_unwind(operation) {
        Ok(Ok(())) => 0,
        Ok(Err(message)) => {
            let _ = write_buffer(error, message);
            -1
        }
        Err(_) => {
            let _ = write_buffer(error, "panic across iOS bridge boundary".to_string());
            -1
        }
    }
}

fn runtime_ref<'a>(runtime: *mut CodexAgentIosRuntime) -> Result<&'a CodexAgentIosRuntime, String> {
    if runtime.is_null() {
        return Err("iOS runtime pointer is null".to_string());
    }
    Ok(unsafe { &*runtime })
}

fn read_bytes<'a>(data: *const u8, length: usize) -> Result<&'a [u8], String> {
    if length == 0 {
        return Ok(&[]);
    }
    if data.is_null() {
        return Err("byte pointer is null".to_string());
    }
    Ok(unsafe { slice::from_raw_parts(data, length) })
}

fn read_utf8<'a>(data: *const u8, length: usize) -> Result<&'a str, String> {
    str::from_utf8(read_bytes(data, length)?).map_err(display_error)
}

fn write_buffer(output: *mut CodexAgentIosBuffer, value: String) -> Result<(), String> {
    if output.is_null() {
        return Err("buffer output pointer is null".to_string());
    }
    let mut bytes = value.into_bytes().into_boxed_slice();
    let buffer = CodexAgentIosBuffer {
        data: bytes.as_mut_ptr(),
        length: bytes.len(),
    };
    std::mem::forget(bytes);
    unsafe { output.write(buffer) };
    Ok(())
}

fn start_runtime(paths: RuntimePaths) -> Result<CodexAgentIosRuntime, String> {
    let (command_tx, command_rx) = mpsc::channel(QUEUE_CAPACITY);
    let (event_tx, event_rx) = mpsc::channel(QUEUE_CAPACITY);
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel(1);
    let closing = Arc::new(AtomicBool::new(false));
    let worker_closing = Arc::clone(&closing);
    let worker = std::thread::Builder::new()
        .name("codex-agent-ios".to_string())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build();
            let runtime = match runtime {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = ready_tx.send(Err(display_error(error)));
                    return;
                }
            };
            runtime.block_on(async move {
                match start_app_server(&paths).await {
                    Ok(client) => {
                        let _ = ready_tx.send(Ok(()));
                        run_actor(client, paths, command_rx, event_tx, worker_closing).await;
                    }
                    Err(error) => {
                        let _ = ready_tx.send(Err(error));
                    }
                }
            });
        })
        .map_err(display_error)?;
    match ready_rx.recv().map_err(display_error)? {
        Ok(()) => Ok(CodexAgentIosRuntime {
            command_tx,
            event_rx: Mutex::new(event_rx),
            worker: Mutex::new(Some(worker)),
            closing,
        }),
        Err(error) => {
            let _ = worker.join();
            Err(error)
        }
    }
}

async fn start_app_server(paths: &RuntimePaths) -> Result<InProcessAppServerClient, String> {
    let overrides = safe_config_overrides();
    let mut loader_overrides = LoaderOverrides::default();
    loader_overrides.ignore_user_config = true;
    loader_overrides.ignore_managed_requirements = true;
    loader_overrides.ignore_user_and_project_exec_policy_rules = true;
    loader_overrides.managed_config_path = Some(paths.codex_home.join("disabled-managed.toml"));
    loader_overrides.system_config_path = Some(paths.codex_home.join("disabled-system.toml"));
    loader_overrides.system_requirements_path =
        Some(paths.codex_home.join("disabled-requirements.toml"));
    let config = Arc::new(
        ConfigBuilder::default()
            .codex_home(paths.codex_home.clone())
            .fallback_cwd(Some(paths.workspace.clone()))
            .cli_overrides(overrides.clone())
            .loader_overrides(loader_overrides.clone())
            .build()
            .await
            .map_err(display_error)?,
    );
    let state_db = init_state_db(config.as_ref())
        .await
        .ok_or_else(|| "Codex state database is unavailable".to_string())?;
    InProcessAppServerClient::start_uninitialized(InProcessClientStartArgs {
        arg0_paths: Arg0DispatchPaths::default(),
        config,
        cli_overrides: overrides,
        loader_overrides,
        strict_config: true,
        cloud_config_bundle: CloudConfigBundleLoader::default(),
        feedback: CodexFeedback::new(),
        log_db: None,
        state_db: Some(state_db),
        // Files are exposed only through the workspace-confined dynamic tools below.
        // With no execution environment, Codex cannot advertise process-backed tools.
        environment_manager: Arc::new(EnvironmentManager::without_environments()),
        config_warnings: Vec::new(),
        session_source: SessionSource::Exec,
        enable_codex_api_key_env: false,
        client_name: "codex-agent-ios".to_string(),
        // Required by the upstream argument type but unused by start_uninitialized;
        // the shared JSON-RPC initialize request supplies the real client version.
        client_version: String::new(),
        experimental_api: true,
        mcp_server_openai_form_elicitation: false,
        opt_out_notification_methods: Vec::new(),
        channel_capacity: QUEUE_CAPACITY,
    })
    .await
    .map_err(display_error)
}

fn safe_config_overrides() -> Vec<(String, TomlValue)> {
    [
        (
            "cli_auth_credentials_store",
            TomlValue::String("file".to_string()),
        ),
        ("web_search", TomlValue::String("disabled".to_string())),
        ("features.shell_tool", TomlValue::Boolean(false)),
        ("features.code_mode", TomlValue::Boolean(false)),
        (
            "features.code_mode_buffered_exec",
            TomlValue::Boolean(false),
        ),
        ("features.code_mode_host", TomlValue::Boolean(false)),
        ("features.code_mode_only", TomlValue::Boolean(false)),
        ("features.multi_agent", TomlValue::Boolean(false)),
        ("features.apps", TomlValue::Boolean(false)),
        ("features.enable_mcp_apps", TomlValue::Boolean(false)),
        ("features.plugins", TomlValue::Boolean(false)),
        ("features.hooks", TomlValue::Boolean(false)),
        (
            "features.skill_mcp_dependency_install",
            TomlValue::Boolean(false),
        ),
        ("features.workspace_dependencies", TomlValue::Boolean(false)),
        ("features.standalone_web_search", TomlValue::Boolean(false)),
    ]
    .into_iter()
    .map(|(key, value)| (key.to_string(), value))
    .collect()
}

async fn run_actor(
    mut client: InProcessAppServerClient,
    paths: RuntimePaths,
    mut command_rx: mpsc::Receiver<BridgeCommand>,
    event_tx: mpsc::Sender<BridgeEvent>,
    closing: Arc<AtomicBool>,
) {
    loop {
        tokio::select! {
            command = command_rx.recv() => match command {
                Some(BridgeCommand::Message(message)) => {
                    if let Err(error) = handle_client_message(&client, &paths, message, &event_tx, &closing).await {
                        send_event(&event_tx, &closing, 2, error).await;
                    }
                }
                Some(BridgeCommand::Shutdown) | None => {
                    closing.store(true, Ordering::Release);
                    let result = client.shutdown().await;
                    if let Err(error) = result {
                        let _ = event_tx.send(BridgeEvent { kind: 2, payload: display_error(error) }).await;
                    }
                    let _ = event_tx.send(BridgeEvent { kind: 4, payload: "0".to_string() }).await;
                    break;
                }
            },
            event = client.next_event() => match event {
                Some(event) => handle_server_event(&client, event, &event_tx, &closing).await,
                None => {
                    if !closing.swap(true, Ordering::AcqRel) {
                        let _ = event_tx.send(BridgeEvent { kind: 3, payload: String::new() }).await;
                        let _ = event_tx.send(BridgeEvent { kind: 4, payload: "1".to_string() }).await;
                    }
                    break;
                }
            },
        }
    }
}

async fn handle_client_message(
    client: &InProcessAppServerClient,
    paths: &RuntimePaths,
    message: Vec<u8>,
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
) -> Result<(), String> {
    let mut value: Value = serde_json::from_slice(&message).map_err(display_error)?;
    let method = value
        .get("method")
        .and_then(Value::as_str)
        .map(str::to_string);
    if let Some(method) = method.as_deref() {
        if let Some(capability) = unsupported_client_capability(method) {
            if let Some(id) = value.get("id") {
                let id: RequestId = serde_json::from_value(id.clone()).map_err(display_error)?;
                send_json(
                    event_tx,
                    closing,
                    &JSONRPCError {
                        id,
                        error: unsupported_error(capability),
                    },
                )
                .await?;
            }
            return Ok(());
        }
        sanitize_request(&mut value, method, &paths.workspace)?;
        if value.get("id").is_some() {
            let request: ClientRequest = serde_json::from_value(value).map_err(display_error)?;
            let id = request.id().clone();
            let request_handle = client.request_handle();
            let output = event_tx.clone();
            let closing = Arc::clone(closing);
            tokio::spawn(async move {
                let response = match request_handle.request(request).await {
                    Ok(Ok(result)) => serde_json::to_string(&JSONRPCResponse { id, result }),
                    Ok(Err(error)) => serde_json::to_string(&JSONRPCError { id, error }),
                    Err(error) => serde_json::to_string(&JSONRPCError {
                        id,
                        error: JSONRPCErrorError {
                            code: -32603,
                            message: display_error(error),
                            data: None,
                        },
                    }),
                };
                if let Ok(response) = response {
                    send_event(&output, &closing, 1, response).await;
                }
            });
        } else {
            let notification: ClientNotification =
                serde_json::from_value(value).map_err(display_error)?;
            client.notify(notification).await.map_err(display_error)?;
        }
        return Ok(());
    }

    if value.get("result").is_some() {
        let response: JSONRPCResponse = serde_json::from_value(value).map_err(display_error)?;
        client
            .resolve_server_request(response.id, response.result)
            .await
            .map_err(display_error)
    } else if value.get("error").is_some() {
        let response: JSONRPCError = serde_json::from_value(value).map_err(display_error)?;
        client
            .reject_server_request(response.id, response.error)
            .await
            .map_err(display_error)
    } else {
        Err("invalid App Server JSON-RPC message".to_string())
    }
}

async fn handle_server_event(
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

fn sanitize_request(value: &mut Value, method: &str, workspace: &Path) -> Result<(), String> {
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

fn unsupported_client_capability(method: &str) -> Option<&'static str> {
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

fn unsupported_error(capability: &str) -> JSONRPCErrorError {
    JSONRPCErrorError {
        code: UNSUPPORTED_CAPABILITY_CODE,
        message: format!("Unsupported iOS capability: {capability}"),
        data: Some(json!({ "capability": capability })),
    }
}

async fn send_json(
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
    value: &impl Serialize,
) -> Result<(), String> {
    let message = serde_json::to_string(value).map_err(display_error)?;
    send_event(event_tx, closing, 1, message).await;
    Ok(())
}

async fn send_event(
    event_tx: &mpsc::Sender<BridgeEvent>,
    closing: &Arc<AtomicBool>,
    kind: i32,
    payload: String,
) {
    if !closing.load(Ordering::Acquire) {
        let _ = event_tx.send(BridgeEvent { kind, payload }).await;
    }
}

fn shutdown_runtime(runtime: &CodexAgentIosRuntime) -> Result<(), String> {
    if !runtime.closing.swap(true, Ordering::AcqRel) {
        runtime
            .command_tx
            .blocking_send(BridgeCommand::Shutdown)
            .map_err(|_| "iOS runtime command queue is closed".to_string())?;
    }
    if let Some(worker) = runtime
        .worker
        .lock()
        .map_err(|_| "iOS runtime worker lock is poisoned".to_string())?
        .take()
    {
        worker
            .join()
            .map_err(|_| "iOS runtime worker panicked".to_string())?;
    }
    Ok(())
}

fn execute_workspace_tool(workspace: &Path, tool: &str, arguments: Value) -> WorkspaceToolResult {
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

#[derive(Deserialize)]
struct ApplyPatchArguments {
    patch: String,
}

fn apply_workspace_patch(workspace: &Path, arguments: Value) -> Result<String, String> {
    let arguments: ApplyPatchArguments =
        serde_json::from_value(arguments).map_err(display_error)?;
    if arguments.patch.len() > MAX_PATCH_BYTES {
        return Err("apply_patch input exceeds 1 MiB".to_string());
    }
    let cwd = PathUri::from_host_native_path(workspace).map_err(display_error)?;
    let file_system = WorkspaceFileSystem::new(workspace.to_path_buf());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(display_error)?;
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    match runtime.block_on(codex_apply_patch::apply_patch(
        &arguments.patch,
        &cwd,
        &mut stdout,
        &mut stderr,
        &file_system,
        /*sandbox*/ None,
    )) {
        Ok(_) => String::from_utf8(stdout).map_err(display_error),
        Err(error) => {
            let detail = String::from_utf8_lossy(&stderr).trim().to_string();
            Err(if detail.is_empty() {
                error.to_string()
            } else {
                detail
            })
        }
    }
}

struct WorkspaceFileSystem {
    workspace: PathBuf,
    inner: LocalFileSystem,
}

impl WorkspaceFileSystem {
    fn new(workspace: PathBuf) -> Self {
        Self {
            workspace,
            inner: LocalFileSystem::unsandboxed(),
        }
    }

    fn relative_path(&self, path: &PathUri) -> io::Result<PathBuf> {
        let absolute = path.to_abs_path()?.into_path_buf();
        let relative = absolute.strip_prefix(&self.workspace).map_err(|_| {
            io::Error::new(
                io::ErrorKind::PermissionDenied,
                "apply_patch path is outside the local iOS workspace",
            )
        })?;
        validate_workspace_relative_path(relative)?;
        Ok(relative.to_path_buf())
    }

    fn existing_uri(&self, path: &PathUri) -> io::Result<PathUri> {
        let relative = self.relative_path(path)?;
        let resolved = resolve_existing_path(&self.workspace, &relative)?;
        PathUri::from_host_native_path(resolved)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))
    }

    fn write_path(&self, path: &PathUri) -> io::Result<PathBuf> {
        resolve_for_write_path(&self.workspace, &self.relative_path(path)?)
    }

    fn directory_path(&self, path: &PathUri) -> io::Result<PathBuf> {
        let relative = self.relative_path(path)?;
        validate_workspace_relative_path(&relative)?;
        reject_symlink_components(&self.workspace, &relative)?;
        let joined = self.workspace.join(relative);
        let mut ancestor = joined.as_path();
        while !ancestor.exists() {
            ancestor = ancestor.parent().ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "directory escapes workspace",
                )
            })?;
        }
        let canonical = ancestor.canonicalize()?;
        if !canonical.starts_with(&self.workspace) {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "directory escapes the local iOS workspace",
            ));
        }
        Ok(joined)
    }
}

impl ExecutorFileSystem for WorkspaceFileSystem {
    fn canonicalize<'a>(
        &'a self,
        path: &'a PathUri,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, PathUri> {
        Box::pin(async move { self.existing_uri(path) })
    }

    fn read_file<'a>(
        &'a self,
        path: &'a PathUri,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, Vec<u8>> {
        Box::pin(async move {
            let path = self.existing_uri(path)?;
            let metadata = self.inner.get_metadata(&path, None).await?;
            if !metadata.is_file || metadata.size > MAX_FILE_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "apply_patch requires files no larger than 4 MiB",
                ));
            }
            self.inner.read_file(&path, None).await
        })
    }

    fn read_file_stream<'a>(
        &'a self,
        path: &'a PathUri,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, FileSystemReadStream> {
        Box::pin(async move {
            let path = self.existing_uri(path)?;
            self.inner.read_file_stream(&path, None).await
        })
    }

    fn write_file<'a>(
        &'a self,
        path: &'a PathUri,
        contents: Vec<u8>,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, ()> {
        Box::pin(async move {
            if contents.len() as u64 > MAX_FILE_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "apply_patch content exceeds 4 MiB",
                ));
            }
            atomic_write(&self.write_path(path)?, &contents)
        })
    }

    fn create_directory<'a>(
        &'a self,
        path: &'a PathUri,
        options: CreateDirectoryOptions,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, ()> {
        Box::pin(async move {
            let path = self.directory_path(path)?;
            if options.recursive {
                fs::create_dir_all(path)
            } else {
                fs::create_dir(path)
            }
        })
    }

    fn get_metadata<'a>(
        &'a self,
        path: &'a PathUri,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, FileMetadata> {
        Box::pin(async move {
            let path = self.existing_uri(path)?;
            self.inner.get_metadata(&path, None).await
        })
    }

    fn read_directory<'a>(
        &'a self,
        path: &'a PathUri,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, Vec<ReadDirectoryEntry>> {
        Box::pin(async move {
            let path = self.existing_uri(path)?;
            self.inner.read_directory(&path, None).await
        })
    }

    fn remove<'a>(
        &'a self,
        path: &'a PathUri,
        options: RemoveOptions,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, ()> {
        Box::pin(async move {
            if options.recursive {
                return Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "recursive removal is unavailable to apply_patch",
                ));
            }
            let relative = self.relative_path(path)?;
            let path = resolve_existing_path(&self.workspace, &relative);
            match path {
                Ok(path) => fs::remove_file(path),
                Err(error) if options.force && error.kind() == io::ErrorKind::NotFound => Ok(()),
                Err(error) => Err(error),
            }
        })
    }

    fn copy<'a>(
        &'a self,
        _source_path: &'a PathUri,
        _destination_path: &'a PathUri,
        _options: CopyOptions,
        _sandbox: Option<&'a FileSystemSandboxContext>,
    ) -> ExecutorFileSystemFuture<'a, ()> {
        Box::pin(async {
            Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "copy is unavailable to apply_patch",
            ))
        })
    }
}

#[derive(Deserialize)]
struct ReadFileArguments {
    path: String,
    #[serde(default)]
    offset: usize,
    limit: Option<usize>,
}

fn read_file(workspace: &Path, arguments: Value) -> Result<String, String> {
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

fn list_directory(workspace: &Path, arguments: Value) -> Result<String, String> {
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
struct SearchLimits {
    files: usize,
    directories: usize,
    depth: usize,
    scanned_bytes: u64,
    results: usize,
    output_bytes: usize,
}

const SEARCH_LIMITS: SearchLimits = SearchLimits {
    files: MAX_SEARCH_FILES,
    directories: MAX_SEARCH_DIRECTORIES,
    depth: MAX_SEARCH_DEPTH,
    scanned_bytes: MAX_SEARCH_SCANNED_BYTES,
    results: MAX_SEARCH_RESULTS,
    output_bytes: MAX_SEARCH_OUTPUT_BYTES,
};

#[derive(Default)]
struct SearchState {
    files: usize,
    directories: usize,
    scanned_bytes: u64,
    output_bytes: usize,
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

fn search_text(workspace: &Path, arguments: Value) -> Result<String, String> {
    search_text_with_limits(workspace, arguments, SEARCH_LIMITS)
}

fn search_text_with_limits(
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

#[derive(Deserialize)]
struct WriteFileArguments {
    path: String,
    content: String,
}

fn write_file(workspace: &Path, arguments: Value) -> Result<String, String> {
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

fn atomic_write(path: &Path, contents: &[u8]) -> io::Result<()> {
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

fn resolve_existing(workspace: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = validated_relative_path(relative)?;
    resolve_existing_path(workspace, relative).map_err(display_error)
}

fn resolve_for_write(workspace: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = validated_relative_path(relative)?;
    resolve_for_write_path(workspace, relative).map_err(display_error)
}

fn resolve_existing_path(workspace: &Path, relative: &Path) -> io::Result<PathBuf> {
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

fn resolve_for_write_path(workspace: &Path, relative: &Path) -> io::Result<PathBuf> {
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

fn validate_workspace_relative_path(path: &Path) -> io::Result<()> {
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

fn reject_symlink_components(workspace: &Path, relative: &Path) -> io::Result<()> {
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

fn display_error(error: impl std::fmt::Display) -> String {
    error.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

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
        }
    }

    #[test]
    fn configuration_rejects_equal_workspace_and_codex_home() {
        let (sandbox, workspace) = workspace();
        let error = configuration(sandbox.path(), &workspace, &workspace)
            .validate()
            .expect_err("equal paths must fail");
        assert!(error.contains("disjoint"));
    }

    #[test]
    fn configuration_rejects_codex_home_inside_workspace() {
        let (sandbox, workspace) = workspace();
        let nested = workspace.join("state");
        let error = configuration(sandbox.path(), &workspace, &nested)
            .validate()
            .expect_err("nested Codex home must fail");
        assert!(error.contains("disjoint"));
        assert!(!nested.exists(), "rejected Codex home must not be created");
    }

    #[test]
    fn configuration_rejects_workspace_inside_codex_home() {
        let sandbox = TempDir::new().expect("sandbox");
        let codex_home = sandbox.path().join("state");
        let workspace = codex_home.join("workspace");
        fs::create_dir_all(&workspace).expect("nested workspace");
        let error = configuration(sandbox.path(), &workspace, &codex_home)
            .validate()
            .expect_err("nested workspace must fail");
        assert!(error.contains("disjoint"));
    }

    #[test]
    fn configuration_rejects_workspace_outside_sandbox() {
        let sandbox = TempDir::new().expect("sandbox");
        let outside = TempDir::new().expect("outside");
        let codex_home = sandbox.path().join("state");
        let error = configuration(sandbox.path(), outside.path(), &codex_home)
            .validate()
            .expect_err("outside workspace must fail");
        assert!(error.contains("escapes"));
    }

    #[test]
    fn configuration_rejects_codex_home_outside_sandbox() {
        let (sandbox, workspace) = workspace();
        let outside = TempDir::new().expect("outside");
        let error = configuration(sandbox.path(), &workspace, outside.path())
            .validate()
            .expect_err("outside Codex home must fail");
        assert!(error.contains("escapes"));
    }

    #[test]
    fn configuration_accepts_sibling_directories() {
        let (sandbox, workspace) = workspace();
        let codex_home = sandbox.path().join("state");
        let paths = configuration(sandbox.path(), &workspace, &codex_home)
            .validate()
            .expect("sibling paths");
        assert_eq!(paths.workspace, workspace);
        assert_eq!(paths.codex_home, codex_home.canonicalize().expect("Codex home"));
    }

    #[test]
    fn local_tools_read_search_list_and_write_without_processes() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("input.txt"), "alpha\nbeta\n").expect("fixture");

        let read = execute_workspace_tool(&workspace, "read_file", json!({ "path": "input.txt" }));
        assert!(read.success);
        assert_eq!(read.text, "alpha\nbeta\n");

        let search = execute_workspace_tool(&workspace, "search_text", json!({ "query": "BETA" }));
        assert!(search.success);
        assert!(search.text.contains("input.txt:2:beta"));

        let write = execute_workspace_tool(
            &workspace,
            "write_file",
            json!({ "path": "output.txt", "content": "local" }),
        );
        assert!(write.success);
        assert_eq!(
            fs::read_to_string(workspace.join("output.txt")).unwrap(),
            "local"
        );

        let list = execute_workspace_tool(&workspace, "list_directory", json!({}));
        assert!(list.success);
        assert_eq!(list.text, "file\tinput.txt\nfile\toutput.txt");
    }

    #[test]
    fn local_tools_reject_traversal() {
        let (sandbox, workspace) = workspace();
        fs::write(sandbox.path().join("outside.txt"), "secret").expect("fixture");
        let result =
            execute_workspace_tool(&workspace, "read_file", json!({ "path": "../outside.txt" }));
        assert!(!result.success);
        assert!(result.text.contains("must not contain '..'"));
    }

    #[test]
    fn apply_patch_updates_and_adds_files_inside_workspace() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("note.txt"), "alpha\nbeta\n").expect("fixture");
        let result = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: note.txt\n@@\n-alpha\n+patched\n beta\n*** Add File: nested/new.txt\n+created locally\n*** End Patch\n"
            }),
        );

        assert!(result.success, "{}", result.text);
        assert_eq!(
            fs::read_to_string(workspace.join("note.txt")).unwrap(),
            "patched\nbeta\n"
        );
        assert_eq!(
            fs::read_to_string(workspace.join("nested/new.txt")).unwrap(),
            "created locally\n"
        );
    }

    #[test]
    fn apply_patch_rejects_traversal_and_symlinks_and_breaks_hard_links() {
        use std::os::unix::fs::symlink;

        let (sandbox, workspace) = workspace();
        let outside = sandbox.path().join("outside.txt");
        fs::write(&outside, "outside\n").expect("outside fixture");

        let traversal = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: ../outside.txt\n@@\n-outside\n+escaped\n*** End Patch\n"
            }),
        );
        assert!(!traversal.success);
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");

        symlink(&outside, workspace.join("linked.txt")).expect("symlink");
        let linked = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: linked.txt\n@@\n-outside\n+escaped\n*** End Patch\n"
            }),
        );
        assert!(!linked.success);
        assert!(linked.text.contains("symlink"));
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");

        fs::hard_link(&outside, workspace.join("hard.txt")).expect("hard link");
        let hard_link = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: hard.txt\n@@\n-outside\n+workspace only\n*** End Patch\n"
            }),
        );
        assert!(hard_link.success, "{}", hard_link.text);
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");
        assert_eq!(
            fs::read_to_string(workspace.join("hard.txt")).unwrap(),
            "workspace only\n"
        );
    }

    #[test]
    fn configuration_rejects_paths_before_creating_outside_the_sandbox() {
        let sandbox = TempDir::new().expect("sandbox");
        let workspace = sandbox.path().join("workspace");
        fs::create_dir(&workspace).expect("workspace");
        let outside_parent = TempDir::new().expect("outside parent");
        let outside = outside_parent.path().join("state");
        let configuration = RuntimeConfiguration {
            sandbox_root_path: sandbox.path().to_path_buf(),
            workspace_path: workspace,
            codex_home_path: outside.clone(),
        };

        assert!(configuration.validate().is_err());
        assert!(!outside.exists());
    }

    #[test]
    fn search_reports_each_traversal_budget() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("a.txt"), "needle\n").expect("first file");
        fs::write(workspace.join("b.txt"), "needle\n").expect("second file");
        fs::create_dir(workspace.join("nested")).expect("nested directory");
        fs::create_dir(workspace.join("nested/deeper")).expect("deep directory");
        fs::write(workspace.join("nested/deeper/c.txt"), "needle\n").expect("deep file");
        let arguments = json!({ "query": "needle" });

        let files = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                files: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("file budget");
        assert!(files.contains("search_text truncated: visited files budget reached"));

        let directories = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                directories: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("directory budget");
        assert!(directories.contains("search_text truncated: visited directories budget reached"));

        let depth = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                depth: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("depth budget");
        assert!(depth.contains("search_text truncated: recursion depth budget reached"));

        let bytes = search_text_with_limits(
            &workspace,
            arguments,
            SearchLimits {
                scanned_bytes: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("byte budget");
        assert!(bytes.contains("search_text truncated: scanned bytes budget reached"));
    }

    #[test]
    fn capability_profile_rejects_process_git_plugin_and_mcp_routes() {
        let overrides = safe_config_overrides()
            .into_iter()
            .collect::<std::collections::HashMap<_, _>>();
        for feature in [
            "features.code_mode",
            "features.code_mode_buffered_exec",
            "features.code_mode_host",
            "features.code_mode_only",
        ] {
            assert_eq!(overrides.get(feature), Some(&TomlValue::Boolean(false)));
        }
        assert_eq!(
            unsupported_client_capability("command/exec"),
            Some("process execution")
        );
        assert_eq!(
            unsupported_client_capability("thread/shellCommand"),
            Some("process execution")
        );
        assert_eq!(
            unsupported_client_capability("plugin/list"),
            Some("plugins and apps")
        );
        assert_eq!(
            unsupported_client_capability("mcpServerStatus/list"),
            Some("MCP")
        );
        assert_eq!(unsupported_client_capability("thread/start"), None);
    }

    #[test]
    fn runtime_has_no_process_environment() {
        let manager = EnvironmentManager::without_environments();
        assert!(manager.default_environment().is_none());
        assert!(manager.try_local_environment().is_none());
    }

    #[test]
    fn thread_start_is_confined_and_filters_dynamic_tools() {
        let (_sandbox, workspace) = workspace();
        let mut request = json!({
            "id": 1,
            "method": "thread/start",
            "params": {
                "cwd": "/outside",
                "sandbox": "danger-full-access",
                "developerInstructions": "use shell",
                "config": { "features": { "shell_tool": true } },
                "dynamicTools": [
                    { "name": "apply_patch" },
                    { "name": "read_file" },
                    { "name": "run_command" }
                ]
            }
        });
        sanitize_request(&mut request, "thread/start", &workspace).expect("sanitize");
        let params = request["params"].as_object().unwrap();
        assert_eq!(
            params["cwd"],
            Value::String(workspace.to_string_lossy().into_owned())
        );
        assert_eq!(
            params["runtimeWorkspaceRoots"],
            json!([workspace.to_string_lossy()])
        );
        assert_eq!(params["sandbox"], "workspace-write");
        assert_eq!(params["config"]["features"]["shell_tool"], false);
        assert_eq!(params["config"]["features"]["code_mode"], false);
        assert_eq!(
            params["config"]["features"]["code_mode_buffered_exec"],
            false
        );
        assert_eq!(params["config"]["features"]["code_mode_host"], false);
        assert_eq!(params["config"]["features"]["code_mode_only"], false);
        assert_eq!(params["dynamicTools"].as_array().unwrap().len(), 2);
        assert_eq!(params["dynamicTools"][0]["name"], "apply_patch");
        assert_eq!(params["dynamicTools"][1]["name"], "read_file");
    }

    #[test]
    fn turn_start_is_confined_to_workspace_write() {
        let (_sandbox, workspace) = workspace();
        let mut request = json!({
            "id": 2,
            "method": "turn/start",
            "params": {
                "threadId": "thread",
                "input": [],
                "cwd": "/outside",
                "sandboxPolicy": { "type": "dangerFullAccess" },
                "permissions": "unrestricted"
            }
        });

        sanitize_request(&mut request, "turn/start", &workspace).expect("sanitize");
        let params = request["params"].as_object().unwrap();
        assert_eq!(
            params["cwd"],
            Value::String(workspace.to_string_lossy().into_owned())
        );
        assert_eq!(params["sandboxPolicy"]["type"], "workspaceWrite");
        assert_eq!(params["sandboxPolicy"]["networkAccess"], false);
        assert!(!params.contains_key("permissions"));
    }

    #[test]
    fn api_buffers_round_trip_and_free() {
        let mut buffer = CodexAgentIosBuffer::default();
        write_buffer(&mut buffer, "Grüezi".to_string()).expect("buffer");
        let bytes = unsafe { slice::from_raw_parts(buffer.data, buffer.length) };
        assert_eq!(str::from_utf8(bytes).unwrap(), "Grüezi");
        codex_agent_ios_buffer_free(&mut buffer);
        assert!(buffer.data.is_null());
        assert_eq!(buffer.length, 0);
    }

    #[tokio::test]
    async fn embedded_host_leaves_the_handshake_to_the_json_rpc_client() {
        let (sandbox, workspace) = workspace();
        let codex_home = sandbox.path().join("state");
        fs::create_dir(&codex_home).expect("state");
        let paths = RuntimePaths {
            workspace,
            codex_home: codex_home.canonicalize().expect("canonical state"),
        };
        let client = start_app_server(&paths).await.expect("uninitialized host");
        let initialize: ClientRequest = serde_json::from_value(json!({
            "id": 1,
            "method": "initialize",
            "params": {
                "clientInfo": {
                    "name": "bridge-test",
                    "version": "0.0.0",
                    "title": "Bridge Test"
                },
                "capabilities": {
                    "experimentalApi": true,
                    "mcpServerOpenaiFormElicitation": false
                }
            }
        }))
        .expect("initialize request");
        assert!(client.request(initialize).await.expect("transport").is_ok());
        let initialized: ClientNotification =
            serde_json::from_value(json!({ "method": "initialized" }))
                .expect("initialized notification");
        client.notify(initialized).await.expect("initialized");

        let duplicate: ClientRequest = serde_json::from_value(json!({
            "id": 2,
            "method": "initialize",
            "params": {
                "clientInfo": { "name": "duplicate", "version": "0.0.0" },
                "capabilities": { "experimentalApi": true }
            }
        }))
        .expect("duplicate initialize request");
        assert!(client.request(duplicate).await.expect("transport").is_err());
        client.shutdown().await.expect("shutdown");
    }
}
