import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

data class IosNativeTaskConfiguration(
    val codexRevision: String,
    val codexArchiveSha256: String,
    val codexCargoLockSha256: String,
    val resolvedCargoLockSha256: String,
    val libsqlite3SysVersion: String,
    val libsqlite3SysArchiveSha256: String,
    val expectedSqliteSourceSha256: String,
    val expectedPatchedSqliteSourceSha256: String,
    val pinnedRustToolchain: String,
    val pinnedRustSrcComponent: String,
    val rustLibrary: String,
    val minimumIosVersion: String,
    val pinnedSqliteArchiveSha256: String,
    val sqliteArchiveBytes: Long,
    val pinnedReleaseLto: String,
    val pinnedReleaseCodegenUnits: String,
    val pinnedReleaseRustFlags: String,
    val pinnedReleaseRustPathRemapPolicy: Map<String, String>,
)

data class IosNativeTasks(
    val testCodexIosBridge: TaskProvider<PinnedCargoTask>,
    val testCodexIosDirectToolMode: TaskProvider<PinnedCargoTask>,
    val buildCodexIosArm64Rust: TaskProvider<PinnedCargoTask>,
    val buildCodexIosSimulatorArm64Rust: TaskProvider<PinnedCargoTask>,
    val iosArm64RustArchive: Provider<RegularFile>,
    val iosSimulatorArm64RustArchive: Provider<RegularFile>,
    val prepareCodexAgentIosArm64RustSlice: TaskProvider<Task>,
    val prepareCodexAgentIosSimulatorArm64RustSlice: TaskProvider<Task>,
)
