use std::fs;
use std::io;
use std::path::Path;
use std::path::PathBuf;

use codex_exec_server::CopyOptions;
use codex_exec_server::CreateDirectoryOptions;
use codex_exec_server::ExecutorFileSystem;
use codex_exec_server::ExecutorFileSystemFuture;
use codex_exec_server::FileMetadata;
use codex_exec_server::FileSystemReadStream;
use codex_exec_server::FileSystemSandboxContext;
use codex_exec_server::LocalFileSystem;
use codex_exec_server::ReadDirectoryEntry;
use codex_exec_server::RemoveOptions;
use codex_utils_path_uri::PathUri;
use serde::Deserialize;
use serde_json::Value;

use crate::display_error;
use crate::workspace::MAX_FILE_BYTES;
use crate::workspace_write::atomic_write;
use crate::workspace_write::reject_symlink_components;
use crate::workspace_write::resolve_existing_path;
use crate::workspace_write::resolve_for_write_path;
use crate::workspace_write::validate_workspace_relative_path;

const MAX_PATCH_BYTES: usize = 1024 * 1024;

#[derive(Deserialize)]
struct ApplyPatchArguments {
    patch: String,
}

pub(crate) fn apply_workspace_patch(workspace: &Path, arguments: Value) -> Result<String, String> {
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
