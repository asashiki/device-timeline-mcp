# Keep OkHttp / Okio (they ship their own consumer rules; this is belt-and-braces).
-dontwarn okhttp3.**
-dontwarn okio.**

# Workers scheduled by name survive upgrades.
-keepnames class * extends androidx.work.ListenableWorker
