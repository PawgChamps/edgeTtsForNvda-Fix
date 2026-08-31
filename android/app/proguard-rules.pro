# --- Our components referenced from the manifest (kept from being renamed) ---
-keep class com.edgetts.engine.EdgeTtsService { *; }
-keep class com.edgetts.engine.CheckVoiceDataActivity { *; }
-keep class com.edgetts.engine.GetSampleTextActivity { *; }
-keep class com.edgetts.engine.EngineSettingsActivity { *; }

# --- OkHttp / Okio ---
# They ship consumer ProGuard rules, but these silence warnings for optional
# platform integrations they reference reflectively and that we don't use.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep OkHttp's platform classes intact.
-keepnames class okhttp3.internal.platform.ConscryptPlatform
