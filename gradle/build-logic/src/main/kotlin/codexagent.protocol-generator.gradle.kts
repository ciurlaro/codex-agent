import java.io.File

tasks.register<GenerateProtocolTask>("generateProtocol") {
    group = "protocol"
    description = "Extracts exact stable-v2 route descriptors from pinned upstream sources."
    dependsOn(tasks.named("classes"))
    generatorClasspath.from(configurations.named("runtimeClasspath"))
    generatorMainClass.set("io.github.ciurlaro.codexmobile.appserver.generator.ProtocolGeneratorKt")
    commonSource.set(layout.file(providers.gradleProperty("codexProtocolCommon").map(::File)))
    schemaSource.set(layout.file(providers.gradleProperty("codexProtocolSchema").map(::File)))
    threadSource.set(layout.file(providers.gradleProperty("codexProtocolThread").map(::File)))
    turnSource.set(layout.file(providers.gradleProperty("codexProtocolTurn").map(::File)))
    val protocolRoot = project(":codex-agent-client").layout.projectDirectory
    schemaOutput.set(protocolRoot.file("protocol/schema/codex_app_server_protocol.schemas.json"))
    descriptorOutput.set(protocolRoot.file("protocol/schema/descriptors.json"))
    generatedSources.set(
        protocolRoot.dir(
            "src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated",
        ),
    )
    provenanceOutput.set(protocolRoot.file("protocol/schema/provenance.json"))
}
