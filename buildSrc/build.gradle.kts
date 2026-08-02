plugins {
    `kotlin-dsl`
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}
