# BingeMote release shrinking. Compose, OkHttp, kotlinx-coroutines and security-crypto
# ship their own consumer rules; only reflection-facing pieces are kept here.
-keepclassmembers class com.bingemote.BuildConfig { *; }
# security-crypto / Tink probe optional crypto providers reflectively.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
