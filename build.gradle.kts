// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ─── OneDrive-safe build output ───────────────────────────────────────────────
// This project lives inside a OneDrive-synced folder. OneDrive holds file locks
// on the build/ tree while it syncs, which intermittently makes Gradle fail with
// "Unable to delete directory ... build/...". Redirecting the build directory to
// a local (non-synced) path avoids the lock and keeps builds reproducible.
// Override with -PbuildDir=/some/path if desired.
val safeBuildRoot: String =
    (findProperty("buildDir") as? String)
        ?: System.getenv("GHOSTLINK_BUILD_DIR")
        ?: File(System.getProperty("java.io.tmpdir"), "ghostlink-build").absolutePath

rootProject.layout.buildDirectory.set(File(safeBuildRoot, "root"))
subprojects {
    layout.buildDirectory.set(File(safeBuildRoot, name))
}
