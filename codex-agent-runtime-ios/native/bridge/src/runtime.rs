use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::Ordering;

use codex_app_server_client::InProcessAppServerClient;
use codex_app_server_client::InProcessClientStartArgs;
use codex_arg0::Arg0DispatchPaths;
use codex_config::CloudConfigBundleLoader;
use codex_config::LoaderOverrides;
use codex_core::config::ConfigBuilder;
use codex_core::init_state_db;
use codex_exec_server::EnvironmentManager;
use codex_feedback::CodexFeedback;
use codex_protocol::protocol::SessionSource;
use tokio::sync::mpsc;
use toml::Value as TomlValue;

use crate::BridgeCommand;
use crate::CodexAgentIosRuntime;
use crate::actor::run_actor;
use crate::config::CodexHomeLease;
use crate::config::RuntimePaths;
use crate::display_error;

pub(crate) const QUEUE_CAPACITY: usize = 64;

pub(crate) fn start_runtime(
    paths: RuntimePaths,
    codex_home_lease: CodexHomeLease,
) -> Result<CodexAgentIosRuntime, String> {
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
            codex_home_lease: Mutex::new(Some(codex_home_lease)),
        }),
        Err(error) => {
            let _ = worker.join();
            Err(error)
        }
    }
}

pub(crate) async fn start_app_server(paths: &RuntimePaths) -> Result<InProcessAppServerClient, String> {
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

pub(crate) fn safe_config_overrides() -> Vec<(String, TomlValue)> {
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

pub(crate) fn shutdown_runtime(runtime: &CodexAgentIosRuntime) -> Result<(), String> {
    let send_result = if !runtime.closing.swap(true, Ordering::AcqRel) {
        runtime
            .command_tx
            .blocking_send(BridgeCommand::Shutdown)
            .map_err(|_| "iOS runtime command queue is closed".to_string())
    } else {
        Ok(())
    };
    let join_result = runtime
        .worker
        .lock()
        .map_err(|_| "iOS runtime worker lock is poisoned".to_string())
        .and_then(|mut worker| match worker.take() {
            Some(worker) => worker
                .join()
                .map_err(|_| "iOS runtime worker panicked".to_string()),
            None => Ok(()),
        });
    let release_result = runtime
        .codex_home_lease
        .lock()
        .map_err(|_| "iOS Codex home registry lease lock is poisoned".to_string())
        .map(|mut lease| drop(lease.take()));
    send_result.and(join_result).and(release_result)
}
