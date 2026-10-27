// Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningPropertiesFile = providers.gradleProperty("qcamReleaseSigningProperties")
    .orNull?.let { project.file(it) }
val releaseSigningProperties = Properties().apply {
    releaseSigningPropertiesFile?.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val releaseStorePassword = System.getenv("QCAM_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("QCAM_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseSigningConfigured = releaseSigningPropertiesFile?.isFile == true &&
    !releaseSigningProperties.getProperty("storeFile").isNullOrBlank() &&
    !releaseSigningProperties.getProperty("keyAlias").isNullOrBlank() &&
    releaseStorePassword != null && releaseKeyPassword != null

android {
    namespace = "com.wordhint.teleprompter"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.wazza.cuecam"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                val configuredStoreFile = File(releaseSigningProperties.getProperty("storeFile"))
                storeFile = if (configuredStoreFile.isAbsolute) configuredStoreFile
                    else File(requireNotNull(releaseSigningPropertiesFile).parentFile, configuredStoreFile.path)
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}
