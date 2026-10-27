import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ghostlink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ghostlink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ─── Release signing ─────────────────────────────────────────────────
    // Credentials come from keystore.properties (git-ignored) or from the
    // GHOSTLINK_STORE_* environment variables. The debug certificate is never
    // used for a release build: if no release identity is configured, the
    // release build is left unsigned and the task says so explicitly.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(prop: String, env: String): String? =
        (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

    val releaseStoreFile = signingValue("storeFile", "GHOSTLINK_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "GHOSTLINK_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GHOSTLINK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GHOSTLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // enables BuildConfig.DEBUG for log gating
    }
    testOptions {
        // org.json ships as a stub on the JVM unit-test classpath. The scanner/QR
        // payload parsing is plain unit-testable code, so use the real Android
        // implementation (available in the AGP artifacts) for local tests.
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // QR code generation — Nayuki QR Code generator (MIT), vendored as plain Java
    // source in app/src/main/java/io/nayuki/qrcodegen. No Gradle dependency and no
    // network access is involved.

    // QR scanning — CameraX preview/analysis + ML Kit's bundled, on-device barcode
    // decoder. Nothing is downloaded at runtime and no image leaves the device.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    // Instrumentation layer (Compose UI + platform). These compile and are
    // packaged by assembleDebugAndroidTest; running them requires a device or
    // emulator, which is not available in this build environment.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Real org.json implementation for local unit tests. AGP's android.jar stub
    // throws "Method ... not mocked", and the QR payload parser is exercised on
    // the JVM. Test-scope only — nothing is added to the APK.
    testImplementation(libs.org.json)
}
