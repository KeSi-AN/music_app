# Keep rules for NewPipeExtractor, taken from the NewPipe app's own proguard-rules.pro (v0.29.3).

-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# Rhino (runs YouTube's JavaScript for stream URLs)
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

-dontwarn okhttp3.**
-dontwarn okio.**

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# Protobuf (used by the extractor for YouTube requests)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

-keep class org.ocpsoft.prettytime.i18n.Resources* { *; }

# ONNX Runtime calls back into its Java classes from native code
-keep class ai.onnxruntime.** { *; }
