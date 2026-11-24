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

# Remaining release instrumentation boundary closure, enumerated statically
# from the two release-run test sources against the 5b579fb app dex mapping
# (no runtime exercise past RecoveryContinuityTest.kt:11 yet - every prior run
# stopped at the first unresolved reference). Mapping status below is from the
# archived 45375818691 app mapping; identity rows exist for unrenamed classes
# (kotlin.Lazy -> kotlin.Lazy), so NO-ROW means absent and R8$$REMOVED$$CLASS$$
# means inlined away. Every entry keeps the class original-named WITH members:
# constructors/methods/enum fields cross the boundary, names alone do not
# (Function2.invoke overrides, enum static fields, Unit.INSTANCE, facade
# default-arg stubs like thread$default/cancel$default/SupervisorJob$default).
#
# UpgradeConnectionTest.kt:46,63 calls Vlc.status(host,port,password).playing
# (com.bingemote.Vlc NO-ROW; return type VlcStatus already kept above).
-keep class com.bingemote.Vlc { *; }
#
# RecoveryContinuityTest.kt:22-26 calls EncryptedSharedPreferences.create(ctx,
# name, MasterKey.Builder(ctx).setKeyScheme(KeyScheme.AES256_GCM).build(),
# PrefKeyEncryptionScheme.AES256_SIV, PrefValueEncryptionScheme.AES256_GCM).
# Mapping: EncryptedSharedPreferences -> px (renamed); PrefKey/PrefValue
# enums REMOVED (inlined); MasterKey/KeyScheme NO-ROW; MasterKey$Builder
# REMOVED. Keeps force re-inclusion of the removed/inlined classes.
-keep class androidx.security.crypto.EncryptedSharedPreferences { *; }
-keep class androidx.security.crypto.EncryptedSharedPreferences$PrefKeyEncryptionScheme { *; }
-keep class androidx.security.crypto.EncryptedSharedPreferences$PrefValueEncryptionScheme { *; }
-keep class androidx.security.crypto.MasterKey { *; }
-keep class androidx.security.crypto.MasterKey$Builder { *; }
-keep class androidx.security.crypto.MasterKey$KeyScheme { *; }
#
# UpgradeConnectionTest.kt:66-79 calls CoroutineScope(SupervisorJob() +
# Dispatchers.Main), runBlocking { ... } and scope.cancel(). Facades and
# Dispatchers: CoroutineScopeKt/SupervisorJobKt/BuildersKt NO-ROW (facade
# split BuildersKt__BuildersKt REMOVED), Dispatchers renamed zt. Descriptor
# types: Job renamed nb0, CompletableJob and MainCoroutineDispatcher NO-ROW.
# (kotlinx.coroutines.CoroutineContext is a typealias for
# kotlin.coroutines.CoroutineContext - no such class exists.)
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.SupervisorJobKt { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.MainCoroutineDispatcher { *; }
#
# kotlin stdlib boundary: kotlin.concurrent.thread used at
# UpgradeConnectionTest.kt:25-26 (ThreadsKt NO-ROW; default args emit
# thread$default). Lambda-to-Unit coercion emits kotlin.Unit.INSTANCE (Unit
# renamed zo1). The runBlocking suspend lambda extends
# kotlin.coroutines.jvm.internal.SuspendLambda (renamed ni1) via
# ContinuationImpl (ep) and BaseContinuationImpl (wd), implements Function2
# (renamed p20; invoke must keep its name for the override) and references
# FunctionBase (z20) plus kotlin.coroutines.CoroutineContext (fq) and its
# DefaultImpls.plus for the `context + Dispatchers.Main` operator.
# DebugProbesKt is deliberately NOT kept: it is absent from the app dex while
# the app itself has suspend functions, proving this module compiles without
# probe emission, so the test dex cannot reference it either.
-keep class kotlin.concurrent.ThreadsKt { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.jvm.functions.Function2 { *; }
-keep class kotlin.coroutines.jvm.internal.SuspendLambda { *; }
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-keep class kotlin.coroutines.jvm.internal.BaseContinuationImpl { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }
-keep class kotlin.coroutines.CoroutineContext { *; }
-keep class kotlin.coroutines.CoroutineContext$DefaultImpls { *; }
