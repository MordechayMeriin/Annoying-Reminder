import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/** A build setting from a Gradle property (-PNAME=…) or an environment variable (CI), else [default]. */
fun setting(name: String, default: String = ""): String =
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: default

android {
    namespace = "com.annoyingreminder.app"
    compileSdk = 36

    defaultConfig {
        // The app's permanent identity on Google Play. Never change it after the first upload.
        applicationId = "com.annoyingreminder.app"
        minSdk = 29 // Android 10: the Galaxy S9
        targetSdk = 36 // What Google Play currently requires
        // Every upload to Play needs a higher versionCode; CI passes the run number.
        versionCode = setting("VERSION_CODE", "1").toInt()
        versionName = setting("VERSION_NAME", "0.1.0-dev")

        // Public by design (the web app ships the same two values to the browser). Secrets never go here.
        buildConfigField("String", "SUPABASE_URL", "\"${setting("SUPABASE_URL").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("SUPABASE_ANON_KEY")}\"")
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
            all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
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
