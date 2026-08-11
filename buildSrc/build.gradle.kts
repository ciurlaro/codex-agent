plugins {
    `kotlin-dsl`
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}
