use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::Ordering;

use codex_app_server_client::InProcessAppServerClient;
use codex_app_server_protocol::ClientNotification;
use codex_app_server_protocol::ClientRequest;
use codex_app_server_protocol::JSONRPCError;
use codex_app_server_protocol::JSONRPCErrorError;
use codex_app_server_protocol::JSONRPCResponse;
use codex_app_server_protocol::RequestId;
use serde_json::Value;
use tokio::sync::mpsc;

use crate::BridgeCommand;
use crate::BridgeEvent;
use crate::config::RuntimePaths;
use crate::display_error;
use crate::protocol::handle_server_event;
use crate::protocol::sanitize_request;
use crate::protocol::send_event;
use crate::protocol::send_json;
use crate::protocol::unsupported_client_capability;
use crate::protocol::unsupported_error;

pub(crate) async fn run_actor(
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
