@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.CodexClientInfo
import io.github.codex_agent_labs.codexmobile.agent.CodexHost
import io.github.codex_agent_labs.codexmobile.agent.CodexHostState
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexPlatform
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexWorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.MACH_TASK_BASIC_INFO_COUNT
import platform.darwin.mach_task_basic_info_data_t
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

class IosCodexRuntimeMetricsTest {
    @Test
    fun recordsStartupShutdownAndMemoryReleaseMetrics() = runBlocking {
        TestWorkspace().use { test ->
            val platform = IosCodexPlatform(test.sandboxRoot)
            val startup = mutableListOf<Long>()
            val shutdown = mutableListOf<Long>()
            var coldStartupMillis = 0L
            var idleCurrentResidentBytes = 0L
            var recursiveSearchCurrentResidentBytes = 0L
            repeat(6) { iteration ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = CodexHost(
                    platform,
                    scope,
                    CodexClientInfo("ios-runtime-metrics", "iOS Runtime Metrics", "0.2.0"),
                )
                val startMark = kotlin.time.TimeSource.Monotonic.markNow()
                if (iteration == 0) {
                    host.selectWorkspace(
                        IosCodexWorkspaceSelection(NSURL.fileURLWithPath(test.workspace)),
                    )
                } else {
                    host.start()
                }
                check(host.state.value is CodexHostState.Ready)
                val startupMillis = startMark.elapsedNow().inWholeMilliseconds
                assertTrue(startupMillis < 30_000, "startup took ${startupMillis}ms")
                if (iteration == 0) {
                    coldStartupMillis = startupMillis
                    idleCurrentResidentBytes = currentResidentBytes()
                    repeat(500) { index ->
                        NSFileManager.defaultManager.createFileAtPath(
                            "${test.workspace}/metric-$index.txt",
                            null,
                            null,
                        )
                    }
                    val tools = IosCodexRuntimeFactory(test.configuration).workspaceTools
                    assertTrue(
                        tools.call(test, "search_text", json("query" to "missing-metric-value")).success,
                    )
                    recursiveSearchCurrentResidentBytes = currentResidentBytes()
                } else {
                    startup += startupMillis
                }
                val shutdownMark = kotlin.time.TimeSource.Monotonic.markNow()
                host.close()
                scope.cancel()
                val shutdownMillis = shutdownMark.elapsedNow().inWholeMilliseconds
                assertTrue(shutdownMillis < 5_000, "shutdown took ${shutdownMillis}ms")
                if (iteration > 0) shutdown += shutdownMillis
            }
            writeRuntimeMetrics(
                coldStartupMillis = coldStartupMillis,
                startup = startup,
                shutdown = shutdown,
                idleCurrentResidentBytes = idleCurrentResidentBytes,
                recursiveSearchCurrentResidentBytes = recursiveSearchCurrentResidentBytes,
            )
        }
    }

}

private fun currentResidentBytes(): Long = memScoped {
    val info = alloc<mach_task_basic_info_data_t>()
    val count = alloc<UIntVar>()
    count.value = MACH_TASK_BASIC_INFO_COUNT.toUInt()
    check(
        task_info(
            mach_task_self_,
            MACH_TASK_BASIC_INFO.toUInt(),
            info.ptr.reinterpret(),
            count.ptr,
        ) == KERN_SUCCESS,
    )
    info.resident_size.toLong()
}

private fun writeRuntimeMetrics(
    coldStartupMillis: Long,
    startup: List<Long>,
    shutdown: List<Long>,
    idleCurrentResidentBytes: Long,
    recursiveSearchCurrentResidentBytes: Long,
) {
    val output = getenv("CODEX_AGENT_IOS_METRICS_PATH")?.toKString() ?: return
    fun median(values: List<Long>) = values.sorted()[values.size / 2]
    val json = """
        {
          "warmupCycles": 1,
          "measuredCycles": 5,
          "coldStartupMilliseconds": $coldStartupMillis,
          "startupMilliseconds": $startup,
          "startupMedianMilliseconds": ${median(startup)},
          "startupMaximumMilliseconds": ${startup.max()},
          "shutdownMilliseconds": $shutdown,
          "shutdownMedianMilliseconds": ${median(shutdown)},
          "shutdownMaximumMilliseconds": ${shutdown.max()},
          "memoryMeasurement": "mach_task_basic_info.current_resident_size",
          "idleCurrentResidentBytes": $idleCurrentResidentBytes,
          "recursiveSearchCurrentResidentBytes": $recursiveSearchCurrentResidentBytes,
          "authenticatedTurnPeakResidentBytes": null
        }
    """.trimIndent()
    val file = checkNotNull(fopen(output, "w")) { "Could not write iOS runtime metrics" }
    try {
        check(fputs(json, file) >= 0)
    } finally {
        fclose(file)
    }
}
