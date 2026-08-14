import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsNodeSupervisorSourceContractTest {
    @Test
    fun `supervisor uses atomic job containment fixed process launch and reproducible hardening`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val sourceDirectory = repository.resolve("codex-agent-runtime-node/src/windowsSupervisor")
        val source = sourceDirectory.resolve("supervisor.c").readText()
        val cmake = sourceDirectory.resolve("CMakeLists.txt").readText()

        listOf(
            "argc != 2", "CreateJobObjectW", "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE",
            "PROC_THREAD_ATTRIBUTE_HANDLE_LIST", "PROC_THREAD_ATTRIBUTE_JOB_LIST",
            "CREATE_SUSPENDED", "EXTENDED_STARTUPINFO_PRESENT", "CreateProcessW(",
            "ResumeThread", "WaitForSingleObject", "GetExitCodeProcess",
        ).forEach { assertTrue(it in source, "Missing supervisor contract: $it") }
        listOf("system(", "WinExec(", "ShellExecute", "CreateProcessA(", "AssignProcessToJobObject(").forEach {
            assertFalse(it in source, "Forbidden non-atomic or shell process API: $it")
        }
        listOf(
            "if(NOT MSVC)", "CMAKE_SIZEOF_VOID_P", "MSVC_RUNTIME_LIBRARY MultiThreaded",
            "/W4", "/WX", "/sdl", "/guard:cf", "/Brepro", "/INCREMENTAL:NO",
            "/DYNAMICBASE", "/NXCOMPAT", "/HIGHENTROPYVA", "/CETCOMPAT",
        ).forEach { assertTrue(it in cmake, "Missing build hardening contract: $it") }
        assertTrue(source.lineSequence().count() <= 300)
        assertTrue(cmake.lineSequence().count() <= 100)
    }
}
