import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private data class MavenArtifactSpec(val artifactId: String, val suffixes: List<String>)

internal const val OLD_MAVEN_GROUP = "io.github.ciurlaro"
internal val mavenRelocationArtifactIds = sortedSetOf(
    "codex-agent-client",
    "codex-agent-client-android",
    "codex-agent-client-iosarm64",
    "codex-agent-client-iossimulatorarm64",
    "codex-agent-client-jvm",
    "codex-agent-runtime-android",
)

private val mavenArtifactSpecs = listOf(
    MavenArtifactSpec("codex-agent-client", listOf("-javadoc.jar", "-kotlin-tooling-metadata.json", "-sources.jar", ".jar", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-android", listOf("-javadoc.jar", "-sources.jar", ".aar", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-iosarm64", listOf("-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-iossimulatorarm64", listOf("-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-js", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-jvm", listOf("-javadoc.jar", "-sources.jar", ".jar", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-linuxarm64", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-linuxx64", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-macosarm64", listOf("-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-macosx64", listOf("-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-mingwx64", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-client-wasm-js", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-android", listOf("-javadoc.jar", "-sources.jar", ".aar", ".module", ".pom")),
    MavenArtifactSpec(
        "codex-agent-runtime-desktop",
        listOf(
            "-app-server-linux-arm64.zip",
            "-app-server-linux-x64.zip",
            "-app-server-macos-arm64.zip",
            "-app-server-macos-x64.zip",
            "-app-server-windows-x64.zip",
            "-javadoc.jar",
            "-kotlin-tooling-metadata.json",
            "-sources.jar",
            ".jar",
            ".module",
            ".pom",
        ),
    ),
    MavenArtifactSpec("codex-agent-runtime-desktop-jvm", listOf("-javadoc.jar", "-sources.jar", ".jar", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-desktop-linuxarm64", listOf("-cinterop-codexDesktop.klib", "-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-desktop-linuxx64", listOf("-cinterop-codexDesktop.klib", "-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-desktop-macosarm64", listOf("-cinterop-codexDesktop.klib", "-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-desktop-macosx64", listOf("-cinterop-codexDesktop.klib", "-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-desktop-mingwx64", listOf("-cinterop-codexDesktop.klib", "-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-ios", listOf("-javadoc.jar", "-kotlin-tooling-metadata.json", "-sources.jar", ".jar", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-ios-iosarm64", listOf("-cinterop-codexAgentIos.klib", "-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-ios-iossimulatorarm64", listOf("-cinterop-codexAgentIos.klib", "-javadoc.jar", "-metadata.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec(
        "codex-agent-runtime-node",
        listOf(
            "-javadoc.jar",
            "-kotlin-tooling-metadata.json",
            "-sources.jar",
            ".jar",
            ".module",
            ".pom",
        ),
    ),
    MavenArtifactSpec("codex-agent-runtime-node-js", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
    MavenArtifactSpec("codex-agent-runtime-node-wasm-js", listOf("-javadoc.jar", "-sources.jar", ".klib", ".module", ".pom")),
)

private val checksumAlgorithms = linkedMapOf(
    ".md5" to "MD5",
    ".sha1" to "SHA-1",
    ".sha256" to "SHA-256",
    ".sha512" to "SHA-512",
)

internal fun expectedMavenPrimaryPaths(version: String): Set<String> = mavenArtifactSpecs.flatMap { spec ->
    spec.suffixes.map { suffix ->
        "${spec.artifactId}/$version/${spec.artifactId}-$version$suffix"
    }
}.toSortedSet()

internal fun expectedMavenRelocationPaths(version: String): Set<String> = mavenRelocationArtifactIds.mapTo(sortedSetOf()) {
    "${OLD_MAVEN_GROUP.replace('.', '/')}/$it/$version/$it-$version.pom"
}

internal fun generateMavenRelocationPoms(outputDirectory: File, newGroup: String, version: String) {
    mavenRelocationArtifactIds.forEach { artifactId ->
        outputDirectory.resolve("$artifactId/$version/$artifactId-$version.pom").apply {
            parentFile.mkdirs()
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                    |<project xmlns="http://maven.apache.org/POM/4.0.0"
                    |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    |  <modelVersion>4.0.0</modelVersion>
                    |  <groupId>$OLD_MAVEN_GROUP</groupId>
                    |  <artifactId>$artifactId</artifactId>
                    |  <version>$version</version>
                    |  <name>$artifactId relocation</name>
                    |  <url>https://github.com/${CodexAgentBuild.REPOSITORY}</url>
                    |  <licenses><license>
                    |    <name>GNU General Public License v3.0 or later</name>
                    |    <url>https://www.gnu.org/licenses/gpl-3.0.txt</url>
                    |    <distribution>repo</distribution>
                    |  </license></licenses>
                    |  <distributionManagement><relocation>
                    |    <groupId>$newGroup</groupId>
                    |    <artifactId>$artifactId</artifactId>
                    |    <version>$version</version>
                    |    <message>Codex Agent moved to $newGroup.</message>
                    |  </relocation></distributionManagement>
                    |</project>
                    |""".trimMargin(),
            )
        }
    }
}

internal fun verifyMavenRepository(
    repository: File,
    groupId: String,
    version: String,
    requireSignatures: Boolean,
    inventory: File,
) {
    val groupPath = groupId.replace('.', '/')
    val groupRoot = repository.resolve(groupPath)
    check(groupRoot.isDirectory) { "Maven group is missing: $groupId" }
    val expectedIds = mavenArtifactSpecs.mapTo(sortedSetOf(), MavenArtifactSpec::artifactId)
    val actualIds = groupRoot.listFiles().orEmpty().filter(File::isDirectory).mapTo(sortedSetOf(), File::getName)
    check(actualIds == expectedIds) { "Maven publication set mismatch: expected=$expectedIds actual=$actualIds" }

    val expectedPrimary = expectedMavenPrimaryPaths(version)
    val actualPrimary = actualIds.flatMap { artifactId ->
        val versionDirectory = groupRoot.resolve("$artifactId/$version")
        check(versionDirectory.isDirectory) { "$artifactId version $version is missing" }
        versionDirectory.listFiles().orEmpty().filter { it.isFile && !it.isMavenSidecar() }.map {
            it.relativeTo(groupRoot).invariantSeparatorsPath
        }
    }.toSortedSet()
    check(actualPrimary == expectedPrimary) {
        "Maven primary artifact set mismatch: expected=$expectedPrimary actual=$actualPrimary"
    }

    val relocationRoot = repository.resolve(OLD_MAVEN_GROUP.replace('.', '/'))
    check(relocationRoot.isDirectory) { "Maven relocation group is missing: $OLD_MAVEN_GROUP" }
    val relocationIds = relocationRoot.listFiles().orEmpty().filter(File::isDirectory)
        .mapTo(sortedSetOf(), File::getName)
    check(relocationIds == mavenRelocationArtifactIds) {
        "Maven relocation set mismatch: expected=$mavenRelocationArtifactIds actual=$relocationIds"
    }
    val expectedRelocations = expectedMavenRelocationPaths(version)
    val actualRelocations = relocationIds.flatMap { artifactId ->
        val versionDirectory = relocationRoot.resolve("$artifactId/$version")
        check(versionDirectory.isDirectory) { "$artifactId relocation version $version is missing" }
        versionDirectory.listFiles().orEmpty().filter { it.isFile && !it.isMavenSidecar() }.map {
            it.relativeTo(repository).invariantSeparatorsPath
        }
    }.toSortedSet()
    check(actualRelocations == expectedRelocations) {
        "Maven relocation POM set mismatch: expected=$expectedRelocations actual=$actualRelocations"
    }

    val expectedRootPrimary = expectedPrimary.mapTo(sortedSetOf()) { "$groupPath/$it" } + expectedRelocations
    expectedRootPrimary.forEach { relative ->
        val primary = repository.resolve(relative)
        checksumAlgorithms.forEach { (suffix, algorithm) ->
            val checksum = primary.releaseDigest(algorithm)
            primary.resolveSibling(primary.name + suffix).writeText("$checksum\n")
        }
        checksumAlgorithms.forEach { (suffix, algorithm) ->
            val sidecar = primary.resolveSibling(primary.name + suffix)
            check(sidecar.readText().trim() == primary.releaseDigest(algorithm)) {
                "${sidecar.name} does not match ${primary.name}"
            }
        }
        if (requireSignatures) {
            check(primary.resolveSibling(primary.name + ".asc").isFile) { "${primary.name}.asc is missing" }
        }
        if (primary.extension == "pom") verifyGplPom(primary)
    }

    check(!inventory.canonicalFile.toPath().startsWith(repository.canonicalFile.toPath())) {
        "Maven inventory must be outside the staged repository"
    }
    val expectedFiles = expectedRootPrimary.flatMapTo(sortedSetOf()) { primary ->
        buildList {
            add(primary)
            checksumAlgorithms.keys.forEach { add(primary + it) }
            if (requireSignatures) add("$primary.asc")
        }
    }
    val actualFiles = Files.walk(repository.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).filter {
            centralExclusion(it.toFile()) == null
        }.map {
            repository.toPath().relativize(it).toString().replace(File.separatorChar, '/')
        }.toList().toSortedSet()
    }
    check(actualFiles == expectedFiles) {
        "Maven regular-file set mismatch: expected=$expectedFiles actual=$actualFiles"
    }
    val files = expectedFiles.map(repository::resolve)
    inventory.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("groupId", JsonPrimitive(groupId))
        put("version", JsonPrimitive(version))
        put("artifactIds", buildJsonArray { expectedIds.forEach { add(JsonPrimitive(it)) } })
        put("relocationGroupId", JsonPrimitive(OLD_MAVEN_GROUP))
        put("relocationArtifactIds", buildJsonArray {
            mavenRelocationArtifactIds.forEach { add(JsonPrimitive(it)) }
        })
        put("primaryArtifactCount", JsonPrimitive(expectedRootPrimary.size))
        put("signaturesRequired", JsonPrimitive(requireSignatures))
        put("files", buildJsonArray {
            files.forEach { file ->
                val relative = file.relativeTo(repository).invariantSeparatorsPath
                add(buildJsonObject {
                    put("path", JsonPrimitive(relative))
                    put("bytes", JsonPrimitive(file.length()))
                    put("sha256", JsonPrimitive(file.releaseDigest()))
                })
            }
        })
    })
}

private fun File.isMavenSidecar(): Boolean = name.endsWith(".asc") || checksumAlgorithms.keys.any(name::endsWith)

private fun verifyGplPom(pom: File) {
    val factory = secureDocumentBuilderFactory(namespaceAware = true)
    val licenses = factory.newDocumentBuilder().parse(pom).getElementsByTagNameNS("*", "license")
    val valid = (0 until licenses.length).map { licenses.item(it) }.any { license ->
        fun value(name: String): String = (license as org.w3c.dom.Element)
            .getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty().trim()
        value("name") == "GNU General Public License v3.0 or later" &&
            value("url") == "https://www.gnu.org/licenses/gpl-3.0.txt" &&
            value("distribution") == "repo"
    }
    check(valid) { "Maven POM has missing or changed licence metadata: ${pom.name}" }
}
