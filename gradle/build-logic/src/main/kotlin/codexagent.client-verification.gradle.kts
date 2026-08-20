val pinnedProtocolSchema = layout.projectDirectory.file(
    "protocol/schema/codex_app_server_protocol.v2.schemas.json",
)
val pinnedCompleteProtocolSchema = layout.projectDirectory.file(
    "protocol/schema/codex_app_server_protocol.schemas.json",
)
val protocolProvenance = layout.projectDirectory.file("protocol/schema/provenance.json")

val verifyProtocolSource = tasks.register<VerifyProtocolSourceTask>("verifyProtocolSource") {
    protocolSchema.set(pinnedProtocolSchema)
    completeProtocolSchema.set(pinnedCompleteProtocolSchema)
    provenance.set(protocolProvenance)
    descriptor.set(layout.projectDirectory.file("protocol/schema/descriptors.json"))
    generatedSources.set(
        layout.projectDirectory.dir(
            "src/commonMain/kotlin/io/github/codex_agent_labs/codexmobile/appserver/protocol/generated",
        ),
    )
    expectedSchemaSha256.set("32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4")
    expectedCompleteSchemaSha256.set("8039a1222460b3846a3688c61eb4b2626b451d61b9c2b36b83fea0ce341ce0be")
}

tasks.register("updateProtocol") {
    group = "protocol"
    description = "Regenerates the pinned protocol from exact Codex sources."
    dependsOn(":tooling:protocol-generator:generateProtocol")
}

tasks.named("check").configure { dependsOn(verifyProtocolSource) }
