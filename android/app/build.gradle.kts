import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ksp)
}

/** android/local.properties: this machine's own settings (Android Studio keeps sdk.dir there; git ignores it). */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * A build setting from a Gradle property (-PNAME=…), android/local.properties (your PC),
 * or an environment variable (CI), else [default]. Blank counts as not set (an unset GitHub variable is blank).
 */
fun setting(name: String, default: String = ""): String =
    listOf(project.findProperty(name) as String?, localProperties.getProperty(name), System.getenv(name))
        .firstOrNull { !it.isNullOrBlank() } ?: default

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

        // Public by design (anyone can read them out of the app). Secrets never go here.
        // The backend: the Lambda function URL (sign-in, the backup, the assistant's model calls). No default: the
        // release build refuses to run without it (see below), so an app never points at a server that's gone.
        buildConfigField("String", "API_URL", "\"${setting("API_URL").trimEnd('/')}\"")
        // Google sign-in: the OAuth client id of type "Web application" (GOOGLE_CLIENT_ID on the Lambda).
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${setting("GOOGLE_WEB_CLIENT_ID")}\"")
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

// A release for Play must know its backend: the GitHub variable API_URL (the Lambda's function URL, printed by
// backend/scripts/aws_lambda_setup.py). Render, the old backend, is deleted (Phase F), so its address is refused too.
val releaseApiUrl = setting("API_URL")
tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease") {
        doFirst {
            val url = releaseApiUrl
            check(url.startsWith("https://") && "onrender.com" !in url) {
                "API_URL must be the Lambda's function URL (https://….lambda-url.<region>.on.aws), not '$url'. " +
                    "Set it in GitHub → Settings → Secrets and variables → Actions → Variables."
            }
        }
    }
}

// The assistant's prompts are the backend's Markdown files (backend/app/prompts/), shipped inside the app as
// assets/prompts/ so it works without the server; a newer set downloaded at the daily backup takes precedence.
abstract class CopyPrompts : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = output.get().asFile
        out.deleteRecursively()
        val src = source.get().asFile
        src.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { f ->
            f.copyTo(File(out, "prompts/" + f.relativeTo(src).invariantSeparatorsPath), overwrite = true)
        }
    }
}

val copyPrompts = tasks.register<CopyPrompts>("copyPrompts") {
    source.set(rootProject.layout.projectDirectory.dir("../backend/app/prompts"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyPrompts, CopyPrompts::output) }
}

// Room writes each database version's schema here, so a later version's migration can be checked against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    // The phone's database (Room, over SQLite) and background jobs (WorkManager: the backup upload).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    // Shabbat and holiday times (candle lighting, nightfall): the KosherJava Zmanim library.
    implementation(libs.zmanim)
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
    testImplementation(libs.work.testing)
}
