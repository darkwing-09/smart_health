import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // JVM screenshot tests of real Compose screens: ./gradlew recordPaparazziDebug / verifyPaparazziDebug
    alias(libs.plugins.paparazzi)
}

// Release signing comes from keystore.properties (git-ignored). Without it, release builds are unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
/** Release task in this run → per-ABI APK splits; otherwise one debug APK filtered to phone + emulator ABIs. */
val releaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val apiUrl = (findProperty("fitagent.apiUrl") as String?) ?: "http://10.0.2.2:8000/"

/**
 * Debug builds talk to the laptop that built them: its Wi-Fi/LAN IPv4 (Docker bridges 172.x skipped) on the
 * docker-compose port. Override with -Pfitagent.debugApiUrl=… ; falls back to the emulator host.
 */
val debugApiUrl: String = (findProperty("fitagent.debugApiUrl") as String?) ?: run {
    val port = (findProperty("fitagent.apiPort") as String?) ?: "8010"
    val lan = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("docker") && !it.name.startsWith("br-") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
    }.getOrNull()
    if (lan != null) "http://$lan:$port/" else "http://10.0.2.2:$port/"
}

android {
    namespace = "com.fitagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fitagent.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Host for challenge invite App Links (https://<host>/j/CODE); the backend serves assetlinks.json.
        manifestPlaceholders["inviteHost"] = (findProperty("fitagent.inviteHost") as String?)
            ?: URI(apiUrl).host.takeUnless { it == "10.0.2.2" } ?: "fitagent.app"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_API_URL", "\"$debugApiUrl\"")
            // Phones (arm64) and emulators (x86_64) only: keeps the single debug APK smaller.
            if (!releaseBuild) ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            buildConfigField("String", "DEFAULT_API_URL", "\"$apiUrl\"")
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    // ML Kit's native pose library is ~13 MB per CPU architecture. Release APKs are split per ABI (Play's app bundle
    // does this automatically); debug stays a single APK.
    splits {
        abi {
            isEnable = releaseBuild
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.health.connect)

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Camera rep counter (G6): on-device pose detection; frames never leave the phone and are never stored.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.pose)
    // ML Kit pulls an old androidx.fragment via Play services; Activity Result APIs need ≥ 1.3.
    implementation(libs.fragment.ktx)

    // Wear OS companion: workouts recorded on the watch arrive through the Data Layer (W1–W4).
    implementation(libs.play.wearable)
    implementation(libs.coroutines.play)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
