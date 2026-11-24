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
# Dispatchers.Main), runBlocking { ... } and scope.cancel(). Facade names
# verified against the resolved kotlinx-coroutines-core-jvm-1.8.1 artifact
# (declared at app/build.gradle.kts:85): SupervisorJob() lives in
# SupervisorKt (plain facade, no part class; REMOVED$$534 in the 45375818691
# mapping), runBlocking in the Builders.kt multifile part class
# BuildersKt__BuildersKt (REMOVED$$523; the empty facade BuildersKt is not
# referenced by compiled calls), CoroutineScope()/cancel in CoroutineScopeKt
# (plain facade; its methods show as inlined-provenance rows, the shell was
# removed without a row). Dispatchers renamed zt. Descriptor types: Job
# renamed nb0, CompletableJob and MainCoroutineDispatcher have no rows.
# (kotlinx.coroutines.CoroutineContext is a typealias for
# kotlin.coroutines.CoroutineContext - no such class exists.)
# The facade too: default-arg calls like runBlocking { } compile to
# BuildersKt.runBlocking$default on the FACADE, and the facade was renamed
# (kotlinx.coroutines.a) while the member row missed - run 45391411658
# release-auth-verify NSME. The facade is public (0x0031) and extends
# Object, so keeping it original-named carries no package-chain hazard.
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.SupervisorKt { *; }
-keep class kotlinx.coroutines.BuildersKt__BuildersKt { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.MainCoroutineDispatcher { *; }
#
# kotlin stdlib boundary. UpgradeConnectionTest no longer calls any
# kotlin.collections/text/io/concurrent statics (java.* equivalents
# instead): the app R8 merges or renames those package-private multifile
# part classes, and applymapping cannot bind members without a surviving
# row (run 45382268903 NSME listOf); pinning part names splits the
# package-private superclass chain across packages (run 45386135883
# IllegalAccessError at app startup). Compiled-reference check: the
# rewritten fixture emits only kotlin.jvm.internal.Intrinsics calls
# (keepnames above, member rows present and runtime-proven at run
# 45382268903). Lambda-to-Unit coercion emits kotlin.Unit.INSTANCE (Unit
# renamed zo1). The runBlocking suspend lambda extends
# kotlin.coroutines.jvm.internal.SuspendLambda (renamed ni1) via
# ContinuationImpl (ep) and BaseContinuationImpl (wd), implements Function2
# (renamed p20; invoke must keep its name for the override) and references
# FunctionBase (z20) plus kotlin.coroutines.CoroutineContext (fq) and its
# DefaultImpls.plus for the `context + Dispatchers.Main` operator.
# DebugProbesKt is deliberately NOT kept: it is absent from the app dex while
# the app itself has suspend functions, proving this module compiles without
# probe emission, so the test dex cannot reference it either.
-keep class kotlin.Unit { *; }
-keep class kotlin.jvm.functions.Function2 { *; }
-keep class kotlin.coroutines.jvm.internal.SuspendLambda { *; }
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-keep class kotlin.coroutines.jvm.internal.BaseContinuationImpl { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }
-keep class kotlin.coroutines.CoroutineContext { *; }
-keep class kotlin.coroutines.CoroutineContext$DefaultImpls { *; }
#
# release-auth-verify run 45396409414 (df5dae4) NCDFE
# kotlin.coroutines.intrinsics.IntrinsicsKt at
# UpgradeConnectionTest$verifyNewReleaseAfterUpdate$1.invokeSuspend:
# the state machine's getCOROUTINE_SUSPENDED fetch. The app R8 inlined
# and removed the whole intrinsics chain (usage.txt: facade IntrinsicsKt,
# IntrinsicsKt__IntrinsicsKt (-> R8$$REMOVED$$CLASS$$487),
# IntrinsicsKt__IntrinsicsJvmKt) and the androidTest build has zero
# kotlin/kotlinx definition rows despite blanket -keep kotlin.**/
# kotlinx.** rules (stdlib/coroutines are not program inputs to that
# task - behavioral evidence, not a resolved-input inventory), so the
# facade reference stayed original-named with no resolution target.
# The facade is public (0x0031) but EXTENDS the package-private (0x0020)
# part classes IntrinsicsKt__IntrinsicsKt -> IntrinsicsKt__IntrinsicsJvmKt,
# and getCOROUTINE_SUSPENDED is declared (0x0019 public static final) on
# IntrinsicsKt__IntrinsicsKt, resolved by hierarchy walk: pinning the
# facade alone would split the superclass chain across packages
# (run 45386135883 IllegalAccessError pattern). All three are kept in
# their original package. Residual: uncalled part methods reference
# package-private anonymous inner classes R8 renames; ART resolves
# method bodies lazily and none execute on the test path.
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt { *; }
#
# Run 45403056083 (351263b) NCDFE kotlin.ResultKt at the same
# invokeSuspend (UpgradeConnectionTest.kt:93), one instruction past the
# now-resolving getCOROUTINE_SUSPENDED fetch: ClassNotFoundException
# kotlin.ResultKt, no app mapping row, usage.txt removal - the
# integrated proof. ResultKt is a plain public facade (0x0031, extends
# Object, no part classes, no chain hazard); its own refs (kotlin.Result
# renamed, jvm.internal.Intrinsics kept) are rewritten consistently by
# R8, and the test call site is Object-erased.
-keep class kotlin.ResultKt { *; }
