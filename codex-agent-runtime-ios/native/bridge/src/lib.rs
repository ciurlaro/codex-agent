mod actor;
mod config;
mod protocol;
mod runtime;
mod workspace;
mod workspace_patch;
mod workspace_read;
mod workspace_write;

#[cfg(test)]
mod tests;

use std::ptr;
use std::slice;
use std::str;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::Ordering;
use std::thread::JoinHandle;

use serde_json::Value;
use tokio::sync::mpsc;

use config::CodexHomeLease;
use config::RuntimeConfiguration;
use runtime::shutdown_runtime;
use runtime::start_runtime;
use workspace::execute_workspace_tool;

pub(crate) enum BridgeCommand {
    Message(Vec<u8>),
    Shutdown,
}

pub(crate) struct BridgeEvent {
    pub(crate) kind: i32,
    pub(crate) payload: String,
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
    pub(crate) command_tx: mpsc::Sender<BridgeCommand>,
    pub(crate) event_rx: Mutex<mpsc::Receiver<BridgeEvent>>,
    pub(crate) worker: Mutex<Option<JoinHandle<()>>>,
    pub(crate) closing: Arc<AtomicBool>,
    pub(crate) codex_home_lease: Mutex<Option<CodexHomeLease>>,
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
        let codex_home_lease = CodexHomeLease::acquire(&paths.codex_home)?;
        let native = start_runtime(paths, codex_home_lease)?;
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

pub(crate) fn display_error(error: impl std::fmt::Display) -> String {
    error.to_string()
}
