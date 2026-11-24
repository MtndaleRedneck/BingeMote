# Dependencies provide their own consumer rules. Keep missing-class checks enabled.

# The minified release androidTest APK runs AndroidJUnitRunner inside the app
# process; the runner needs androidx.tracing.Trace at runtime. R8 classified
# tracing as app-provided library code while the app-side R8 stripped it as
# unused, leaving it in neither APK (NoClassDefFoundError in
# AndroidJUnitRunner.onCreate, runs 45344979448 and 45351651419 logcats).
# Keep it on both sides; tracing 1.0.0 is a few KB.
-keep class androidx.tracing.** { *; }

# Instrumentation runtime linkage (shared classloader with the minified
# androidTest APK): AndroidJUnitRunner.registerTestStorage loads
# androidx.test.platform.io.TestDirCalculator (Kotlin, from monitor 1.7.2),
# which resolves these kotlin classes by original name. The app R8 removed the
# LazyKt facade chain as unused and renames the rest (NoClassDefFoundError
# kotlin.LazyKt, runs 45364449159 and 45365558033). Exact closure enumerated
# from the monitor-1.7.2 constant pools plus the stdlib facade superclass
# chain (LazyKt -> LazyKt__LazyKt -> LazyKt__LazyJVMKt); nothing broader.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__LazyKt { *; }
-keep class kotlin.LazyKt__LazyJVMKt { *; }
-keepnames class kotlin.Lazy
-keepnames class kotlin.jvm.functions.Function0
-keepnames class kotlin.jvm.internal.Intrinsics
-keepnames class kotlin.jvm.internal.Lambda

# Minified-release instrumentation boundary: the androidTest APK's classes
# reference these app classes by original name (the test R8 does not rewrite
# them to the app's renamed output - run 45375818691: NoClassDefFoundError
# com.bingemote.Store at RecoveryContinuityTest.kt:11). Kept with members:
# exactly the app classes the release-run instrumentations
# (RecoveryContinuityTest, UpgradeConnectionTest) touch, plus VlcStatus which
# RemoteState.status exposes. CoroutineScope/Continuation are name-preserved
# because the RemoteState constructor and suspend pollOnce descriptors cross
# the same boundary.
-keep class com.bingemote.Store { *; }
-keep class com.bingemote.HelperClient { *; }
-keep class com.bingemote.RemoteState { *; }
-keep class com.bingemote.VlcStatus { *; }
-keepnames class kotlinx.coroutines.CoroutineScope
-keepnames class kotlin.coroutines.Continuation
