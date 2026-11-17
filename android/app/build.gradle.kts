import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/** android/local.properties: this machine's own settings (Android Studio keeps sdk.dir there; git ignores it). */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * A build setting from a Gradle property (-PNAME=…), android/local.properties (your PC),
 * or an environment variable (CI), else [default].
 */
fun setting(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: default

android {
    namespace = "com.prominder.myapp"
    compileSdk = 36

    defaultConfig {
        // The app's permanent identity on Google Play. Never change it after the first upload.
        applicationId = "com.prominder.myapp"
        minSdk = 29 // Android 10: the Galaxy S9
        targetSdk = 36 // What Google Play currently requires
        // Every upload to Play needs a higher versionCode; CI passes the run number.
        versionCode = setting("VERSION_CODE", "1").toInt()
        versionName = setting("VERSION_NAME", "0.1.0-dev")

        // Public by design (the web app ships the same two values to the browser). Secrets never go here.
        buildConfigField("String", "COGNITO_REGION", "\"${setting("COGNITO_REGION")}\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"${setting("COGNITO_CLIENT_ID")}\"")
    }

    signingConfigs {
        // The upload key. CI writes it from a GitHub secret; Google re-signs with the real key (Play App Signing).
        val storePath = setting("ANDROID_KEYSTORE_PATH")
        if (storePath.isNotEmpty() && File(storePath).exists()) {
            create("upload") {
                storeFile = File(storePath)
                storePassword = setting("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = setting("ANDROID_KEY_ALIAS")
                keyPassword = setting("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric (screenshot tests) needs the resources
            all { test ->
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Robolectric downloads Android's system jars from inside the test JVM. Pass on a trust store set for
                // Gradle (e.g. systemProp.javax.net.ssl.trustStoreType=Windows-ROOT behind an HTTPS filter); CI sets none.
                System.getProperty("javax.net.ssl.trustStoreType")?.let { test.systemProperty("javax.net.ssl.trustStoreType", it) }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
