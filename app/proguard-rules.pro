# ─── GhostLink ProGuard / R8 rules ───────────────────────────────────────────
# Keep all GhostLink application classes (safe default; narrow if APK size matters)
-keep class com.ghostlink.app.** { *; }
-keepclassmembers class com.ghostlink.app.** { *; }

# ─── Nayuki QR Code generator (vendored source, io.nayuki.qrcodegen) ─────────
# Pure Java, no reflection. Covered by the com.ghostlink.app.** rules only for app
# code, so keep the package explicitly to be safe under R8.
-keep class io.nayuki.qrcodegen.** { *; }

# ─── Kotlin / Coroutines ─────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ─── Compose ─────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# ─── AndroidX / Navigation ───────────────────────────────────────────────────
-keep class androidx.navigation.** { *; }
-keep class androidx.lifecycle.** { *; }

# ─── JSON (org.json — used by P2PConnectionManager) ──────────────────────────
-keep class org.json.** { *; }

# ─── Strip debug log calls in release builds ─────────────────────────────────
# R8 can inline BuildConfig.DEBUG and eliminate the dead branch, so this rule
# is belt-and-suspenders. Log.d and Log.v are removed; Log.w and Log.e are kept
# because they signal production anomalies that may appear in crash reports.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
