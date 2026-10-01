// Pure Kotlin/JVM: wire framing, clock sync, identity, analysis, export.
// No Android and no protobuf dependency, so it is unit-testable anywhere.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // Conformance vectors shared with the firmware and the Python reference codec.
    systemProperty("retrovision.vectors", rootProject.file("../proto/testvectors/framing.json").absolutePath)
}
