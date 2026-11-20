import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

android {
    namespace = "com.wendinotes.proto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wendinotes.proto"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
            }
            if (!releaseKeystorePassword.isNullOrBlank()) {
                storePassword = releaseKeystorePassword
            }
            if (!releaseKeyAlias.isNullOrBlank()) {
                keyAlias = releaseKeyAlias
            }
            if (!releaseKeyPassword.isNullOrBlank()) {
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

gradle.taskGraph.whenReady {
    val releaseTasks = allTasks.filter { it.project == project && it.name.endsWith("Release") }
    if (releaseTasks.isNotEmpty()) {
        val missing = buildList {
            if (releaseKeystorePath.isNullOrBlank()) add("ANDROID_KEYSTORE_PATH")
            if (releaseKeystorePassword.isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("ANDROID_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("ANDROID_KEY_PASSWORD")
        }
        check(missing.isEmpty()) {
            "Release APK signing is not configured. Set: ${missing.joinToString()}"
        }
        check(file(releaseKeystorePath!!).isFile) {
            "Release keystore not found at ANDROID_KEYSTORE_PATH."
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 通話（PCのプロトと話す）
    implementation("io.livekit:livekit-android:2.26.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
