import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal data class LinuxArmExecutionMember(val path: String, val bytes: Long, val sha256: String)

internal data class LinuxArmExecutionMetadata(
    val commit: String,
    val executableName: String,
    val supervisorExecutableName: String,
    val test: LinuxArmExecutionMember,
    val classifier: LinuxArmExecutionMember,
    val appServer: LinuxArmExecutionMember,
    val supervisor: LinuxArmExecutionMember,
)

internal fun String.linuxArmExecutionMetadata(): LinuxArmExecutionMetadata {
    val json = releaseJson.parseToJsonElement(this) as? JsonObject ?: error("Linux ARM64 metadata is not an object")
    check(json.keys == setOf(
        "schemaVersion", "candidateCommit", "target", "classifier", "executableName",
        "supervisorExecutableName", "testClass", "testMethods", "members",
    )) { "Linux ARM64 metadata fields are invalid" }
    check(json.releaseInt("schemaVersion") == 2 && json.releaseString("target") == "linuxArm64" &&
        json.releaseString("classifier") == "app-server-linux-arm64") { "Linux ARM64 metadata identity is invalid" }
    check(json.releaseString("candidateCommit").matches(Regex("[0-9a-f]{40}"))) {
        "Linux ARM64 candidate commit is not immutable"
    }
    check(json.releaseString("testClass") == DESKTOP_RUNTIME_TEST_CLASS &&
        json.releaseArray("testMethods").map { it.toString().trim('"') }.toSet() == desktopRuntimeTestMethods) {
        "Linux ARM64 metadata test set is invalid"
    }
    val members = json.releaseObject("members")
    fun member(name: String, path: String): LinuxArmExecutionMember {
        val value = members.releaseObject(name)
        check(value.keys == setOf("path", "bytes", "sha256") && value.releaseString("path") == path) {
            "Linux ARM64 metadata member is invalid: $name"
        }
        return LinuxArmExecutionMember(path, value.releaseLong("bytes"), value.releaseString("sha256")).also {
            check(it.bytes >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "Linux ARM64 metadata member hash is invalid: $name"
            }
        }
    }
    check(members.keys == setOf(
        "testExecutable", "classifierArchive", "appServerExecutable", "supervisorExecutable",
    )) { "Linux ARM64 metadata member fields are invalid" }
    return LinuxArmExecutionMetadata(
        json.releaseString("candidateCommit"),
        json.releaseString("executableName"),
        json.releaseString("supervisorExecutableName"),
        member("testExecutable", "linuxArm64-test.kexe"),
        member("classifierArchive", "app-server-linux-arm64.zip"),
        member("appServerExecutable", "codex-app-server"),
        member("supervisorExecutable", "codex-process-supervisor"),
    )
}

internal fun LinuxArmExecutionMetadata.jsonBytes() = (releaseJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("schemaVersion", JsonPrimitive(2)); put("candidateCommit", JsonPrimitive(commit))
        put("target", JsonPrimitive("linuxArm64")); put("classifier", JsonPrimitive("app-server-linux-arm64"))
        put("executableName", JsonPrimitive(executableName))
        put("supervisorExecutableName", JsonPrimitive(supervisorExecutableName))
        put("testClass", JsonPrimitive(DESKTOP_RUNTIME_TEST_CLASS))
        put("testMethods", buildJsonArray { desktopRuntimeTestMethods.forEach { add(JsonPrimitive(it)) } })
        put("members", buildJsonObject {
            fun add(name: String, value: LinuxArmExecutionMember) = put(name, buildJsonObject {
                put("path", JsonPrimitive(value.path)); put("bytes", JsonPrimitive(value.bytes))
                put("sha256", JsonPrimitive(value.sha256))
            })
            add("testExecutable", test); add("classifierArchive", classifier)
            add("appServerExecutable", appServer); add("supervisorExecutable", supervisor)
        })
    },
) + "\n").encodeToByteArray()
