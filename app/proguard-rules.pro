# Project-specific R8 rules (#74).
# Hilt, Room, kotlinx.serialization, OkHttp and Compose ship their own consumer rules,
# and they cover everything that is reflected on here:
#  - Hilt/Dagger: generated components and entry points (dagger.hilt.* consumer rules)
#  - Room: generated *_Impl classes (androidx.room consumer rules)
#  - kotlinx.serialization: @Serializable companions and serializers (kotlinx-serialization-core rules)
#  - OkHttp/Okio: platform probes (okhttp3 consumer rules)
# Add a rule here only for code of our own that needs a class name at runtime.

# Log lines (logcat, and the debug build's Home ⋮ → Export connection log) include state, result and error class
# names, e.g. "state from=Connecting to=Ready" or "error=Timeout(...)". Keep those names readable in release builds,
# so a logcat from a release install can be read without the R8 mapping. Shrinking and optimisation still apply.
-keepnames class io.github.neotheon3.orangebox.core.obd.ObdConnectionState$*
-keepnames class io.github.neotheon3.orangebox.core.obd.ObdConnectionState
-keepnames class io.github.neotheon3.orangebox.core.bluetooth.DongleTestResult$*
-keepnames class io.github.neotheon3.orangebox.core.diagnostics.ScanResult$*
-keepnames class * extends java.lang.Throwable

# Readable stack traces in exported logs and crash reports; retrace with the R8 mapping for the rest.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
