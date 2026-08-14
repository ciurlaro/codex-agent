plugins {
    alias(libs.plugins.kotlin.jvm)
    id("codexagent.protocol-generator")
}
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencyLocking {
    lockAllConfigurations()
}
