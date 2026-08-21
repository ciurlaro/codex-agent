package io.github.codex_agent_labs.codexmobile.appserver.runtime

internal actual fun desktopTestEnvironment(name: String): String? = System.getenv(name)

object JvmRuntimeEvidenceMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected --list-tests or --run-test=<class>.<method>" }
        if (arguments.single() == "--list-tests") {
            println("${DesktopCodexRuntimeTest::class.qualifiedName}.")
            methods.forEach { println("  $it") }
            return
        }
        val selection = arguments.single().removePrefix("--run-test=")
        val prefix = "${DesktopCodexRuntimeTest::class.qualifiedName}."
        require(selection.startsWith(prefix)) { "Unexpected JVM runtime test: $selection" }
        val tests = DesktopCodexRuntimeTest()
        when (val method = selection.removePrefix(prefix)) {
            "closeDuringStartClosesNewProcessExactlyOnce" -> tests.closeDuringStartClosesNewProcessExactlyOnce()
            "initializesAndShutsDownOfficialAppServerWhenProvided" ->
                tests.initializesAndShutsDownOfficialAppServerWhenProvided()
            "rejectsRelativeExecutableBeforeStarting" -> tests.rejectsRelativeExecutableBeforeStarting()
            "rejectsWrongTargetChecksum" -> tests.rejectsWrongTargetChecksum()
            else -> error("Unexpected JVM runtime test method: $method")
        }
    }

    private val methods = sortedSetOf(
        "closeDuringStartClosesNewProcessExactlyOnce",
        "initializesAndShutsDownOfficialAppServerWhenProvided",
        "rejectsRelativeExecutableBeforeStarting",
        "rejectsWrongTargetChecksum",
    )
}
