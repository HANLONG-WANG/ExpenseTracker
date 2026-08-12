# Release-only keep rules are intentionally limited to libraries whose public entry points
# are discovered by Android framework metadata or JNI. Library consumer rules cover the rest.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Commons Compress supports optional codecs that are not used by the ZIP64 backup container.
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**

# This annotation is compile-time metadata only and is not packaged by Commons CSV.
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings

# WorkManager 2.7.1's consumer rule keeps InputMerger classes but does not retain the
# public zero-argument constructor used by its reflective factory after full-mode R8.
-keep public class * extends androidx.work.InputMerger {
    public <init>();
}
