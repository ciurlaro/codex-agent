import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryLayoutContractTest {
    private val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("gradle/build-logic").isDirectory }

    @Test
    fun `explicit build logic and release inputs use the Gradle layout`() {
        val settings = repository.resolve("settings.gradle.kts").readText()
        val rootBuild = repository.resolve("build.gradle.kts").readText()
        val androidBuild = repository.resolve("codex-agent-runtime-android/build.gradle.kts").readText()

        assertTrue("includeBuild(\"gradle/build-logic\")" in settings)
        assertTrue("id(\"codexagent.root-release\")" in rootBuild)
        assertTrue("id(\"codexagent.codex-runtime\")" in androidBuild)
        assertTrue(repository.resolve("gradle/build-logic/src/main/kotlin/codexagent.root-release.gradle.kts").isFile)
        assertTrue(repository.resolve("gradle/build-logic/src/main/kotlin/codexagent.codex-runtime.gradle.kts").isFile)
        assertTrue(repository.resolve("gradle/release/kmp-consumer-template").isDirectory)
        assertTrue(repository.resolve("gradle/kotlin-js-store/package-lock.json").isFile)
        assertTrue(repository.resolve("gradle/kotlin-js-store/wasm/package-lock.json").isFile)
        assertFalse(repository.resolve("build" + "Src").exists())
        assertFalse(repository.resolve("release").exists())
        assertFalse(repository.resolve("kotlin-js-" + "store").exists())
    }

    @Test
    fun `isolated consumer tool versions follow the root catalog`() {
        val catalog = repository.resolve("gradle/libs.versions.toml").readText()
        fun version(name: String) = checkNotNull(
            Regex("(?m)^${Regex.escape(name)} = \"([^\"]+)\"").find(catalog)?.groupValues?.get(1),
        )
        val consumer = repository.resolve("gradle/release/kmp-consumer-template/build.gradle.kts").readText()
        assertTrue("kotlin(\"multiplatform\") version \"${version("kotlin")}\"" in consumer)
        assertTrue("id(\"com.android.kotlin.multiplatform.library\") version \"${version("agp")}\"" in consumer)
    }

    @Test
    fun `Central publishing has one verified transport path`() {
        listOf(
            "codex-agent-client", "codex-agent-runtime-android", "codex-agent-runtime-desktop",
            "codex-agent-runtime-ios", "codex-agent-runtime-node",
        ).forEach { module ->
            assertFalse("publishToMavenCentral(" in repository.resolve("$module/build.gradle.kts").readText(), module)
        }
        val publish = repository.resolve(".github/workflows/publish.yml").readText()
        listOf("prepareCentralDeployment", "awaitCentralValidation", "releaseCentralDeployment")
            .forEach { assertTrue(it in publish, it) }
    }

    @Test
    fun `all internal plugins are explicit and applied only by their owners`() {
        val owners = linkedMapOf(
            "build.gradle.kts" to "root-release",
            "codex-agent-client/build.gradle.kts" to "client-verification",
            "codex-agent-runtime-android/build.gradle.kts" to "codex-runtime",
            "codex-agent-runtime-desktop/build.gradle.kts" to "desktop-runtime",
            "codex-agent-runtime-ios/build.gradle.kts" to "ios-runtime",
            "codex-agent-runtime-node/build.gradle.kts" to "node-runtime",
            "tooling/android-runtime-evidence/build.gradle.kts" to "android-runtime-evidence",
            "tooling/protocol-generator/build.gradle.kts" to "protocol-generator",
        )
        val expectedIds = owners.values.mapTo(sortedSetOf()) { "codexagent.$it" }
        val pluginDirectory = repository.resolve("gradle/build-logic/src/main/kotlin")
        val registeredIds = pluginDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith("codexagent.") && it.name.endsWith(".gradle.kts") }
            .mapTo(sortedSetOf()) { it.name.removeSuffix(".gradle.kts") }
        assertEquals(expectedIds, registeredIds)

        val buildScripts = repository.walkTopDown()
            .onEnter { it == repository || it.name !in setOf(".git", ".gradle", ".codex", ".agents", "build") }
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .associate { it.relativeTo(repository).invariantSeparatorsPath to it.readText() }
        owners.forEach { (owner, suffix) ->
            val id = "codexagent.$suffix"
            val applications = buildScripts.filterValues { "id(\"$id\")" in it }.keys
            assertEquals(setOf(owner), applications, "$id must be applied only by $owner")
        }
        assertEquals(8, owners.size)
    }

    @Test
    fun `live repository text has no legacy root layout references`() {
        val oldBuildLogic = "build" + "Src"
        val oldRelease = "release" + "/"
        val oldJsStore = "kotlin-js-" + "store"
        val rootReleasePath = Regex("""(^|[\s\"'`(=:])(?:\./)?${Regex.escape(oldRelease)}""")
        val rootReleaseName = Regex.escape(oldRelease.removeSuffix("/"))
        val rootReleaseReference = Regex(
            """(?:\b(?:file|dir|resolve)\(\s*[\"'](?:\./)?$rootReleaseName[\"']|""" +
                """(?:^|\s)(?:cd|-p)\s+(?:\./)?$rootReleaseName(?=\s|\z)|""" +
                """[\"']?(?:path|working-directory)[\"']?\s*[:=]\s*[\"']?""" +
                """(?:\./)?$rootReleaseName(?=[\"'\s,}]|\z))""",
        )
        val rootJsStorePath = Regex("""(?<!gradle/)${Regex.escape(oldJsStore)}(?:/|\b)""")
        val ignoredDirectories = setOf(".git", ".gradle", ".codex", ".agents", "build")
        val liveExtensions = setOf("kt", "kts", "java", "md", "yml", "yaml", "json", "toml", "properties")
        val failures = repository.walkTopDown()
            .onEnter { it == repository || it.name !in ignoredDirectories }
            .filter { file ->
                val path = file.relativeTo(repository).invariantSeparatorsPath
                file.isFile && file.extension in liveExtensions && !file.name.endsWith(".local.md") &&
                    !path.startsWith("gradle/build-logic/src/test/")
            }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val stale = oldBuildLogic in line || rootReleasePath.containsMatchIn(line) ||
                        rootReleaseReference.containsMatchIn(line) || rootJsStorePath.containsMatchIn(line)
                    if (stale) "${file.relativeTo(repository)}:${index + 1}: $line" else null
                }
            }.toList()
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `root JS and Wasm tasks use their relocated lock stores`() {
        val rootBuild = repository.resolve("build.gradle.kts").readText()
        val js = rootBuild.substringAfter("withType<NodeJsRootPlugin>")
            .substringBefore("withType<WasmNodeJsRootPlugin>")
        val wasm = rootBuild.substringAfter("withType<WasmNodeJsRootPlugin>")
        assertTrue("getByType(NpmExtension::class.java).lockFileDirectory.set" in js)
        assertTrue("dir(\"gradle/kotlin-js-store\")" in js)
        assertTrue("getByType(WasmNpmExtension::class.java).lockFileDirectory.set" in wasm)
        assertTrue("dir(\"gradle/kotlin-js-store/wasm\")" in wasm)

        val verification = repository.resolve(
            "gradle/build-logic/src/main/kotlin/RepositoryVerificationTasks.kt",
        ).readText()
        assertTrue(":codex-agent-runtime-node:jsNodeTest" in verification)
        assertTrue(":codex-agent-runtime-node:wasmJsNodeTest" in verification)
    }
}
