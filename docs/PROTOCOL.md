# Protocol provenance

The client is generated from OpenAI Codex App Server `0.145.0` at upstream tag
`rust-v0.145.0`, tag object `1635de866c61d1b76e50b31928ee6d61482435a8`,
and revision `25af12f7e61572b0bc18ddb1008be543b91519b0`.

The authoritative provenance, input Git blobs, SHA-256 digests, generator
version, and generated-output digests are recorded in
`codex-agent-client/protocol/schema/provenance.json`. The checked-in stable-v2
schema digest is
`32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4`;
the complete schema digest is
`8039a1222460b3846a3688c61eb4b2626b451d61b9c2b36b83fea0ce341ce0be`.

`./gradlew :codex-agent-client:verifyProtocolSource` verifies those inputs and
all generated outputs. To regenerate, check out the recorded Codex revision and
run `:codex-agent-client:updateProtocol` with the four `codexProtocol*` file
properties listed in the provenance command. Review the complete generated diff
and update identity/runtime pins atomically.
