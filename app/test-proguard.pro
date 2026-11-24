# Keep rules for the MINIFIED androidTest APK only (testProguardFiles).
# The test APK's own R8 pass does not apply proguard-rules.pro, so the
# instrumentation runtime must be kept explicitly here. The app APK is
# unaffected - its rules stay in proguard-rules.pro.
#
# Run 45362517752 @ 2996257: the tracing gate passed, the emulator booted,
# and AndroidJUnitRunner.onCreate died in registerTestStorage with
# ClassNotFoundException: kotlin.LazyKt - the runner's reflectively-loaded
# test-storage service uses Kotlin lazy, invisible to R8's static analysis.
# Keeping the runner/services packages makes those references traceable;
# kotlin.** covers what they pull in. Test-APK size is irrelevant; only the
# app APK is measured.
-keep class androidx.test.** { *; }
-keep class kotlin.** { *; }
-keep class kotlin.jvm.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
