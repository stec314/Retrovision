// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "dev.retrovision.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.retrovision.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("RV_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("RV_VERSION_NAME") ?: "0.1.0-dev"
    }

    signingConfigs {
        // Development key committed in the repo (app/signing): it lets successive CI builds
        // update each other, but it is public, so it proves nothing about who built the APK.
        // Release builds for distribution must pass RV_KEYSTORE* from a real secret.
        create("release") {
            storeFile = file(System.getenv("RV_KEYSTORE") ?: "signing/retrovision-dev.jks")
            storePassword = System.getenv("RV_KEYSTORE_PASSWORD") ?: "retrovision-dev"
            keyAlias = System.getenv("RV_KEY_ALIAS") ?: "retrovision-dev"
            keyPassword = System.getenv("RV_KEY_PASSWORD") ?: "retrovision-dev"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.named("main") {
        // The wire protocol is shared with the firmware and lives at the repository root.
        (this as ExtensionAware).extensions.configure<SourceDirectorySet>("proto") {
            srcDir("../../proto")
        }
        // The wiki (docs/WIKI.md) ships inside the APK, so the in-app guide always matches this build.
        assets.srcDir("../../docs")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.protobuf.javalite)
    implementation(libs.usb.serial)
}
