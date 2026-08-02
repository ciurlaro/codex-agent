# Releasing

1. Run the complete Linux/Android/JVM checks and the macOS iOS compilation gate.
2. Run the Android runtime instrumentation smoke test on an ARM64 Android host.
3. Set a new immutable version in the root build; never reuse a released version.
4. Configure the Maven Central namespace `io.github.ciurlaro` and repository
   secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
   `SIGNING_IN_MEMORY_KEY`, and `SIGNING_IN_MEMORY_KEY_PASSWORD`.
5. Create and publish the matching GitHub release tag. The release workflow runs
   `publishAndReleaseToMavenCentral` and waits for Central validation.
6. Resolve both coordinates from Maven Central in a clean consumer checkout
   before updating host applications.

For migration-only verification, publish the same fixed version to an isolated
repository:

```shell
./gradlew publishAllPublicationsToMigrationRepository \
  -PcodexAgent.localRepository=/tmp/codex-agent-maven
```

Do not commit a local repository, `mavenLocal()`, composite build, source
dependency, or moving version to a consumer.
