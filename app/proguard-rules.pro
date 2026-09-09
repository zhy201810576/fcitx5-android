# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}

# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}

# remove kotlin null checks
-processkotlinnullchecks remove

# ML Kit Digital Ink Recognition + Firebase Components
# R8 会误删通过 META-INF/services 反射加载的组件注册类（ComponentRegistrar），
# 且 optimization 会破坏内部单例（zzk.zza）与组件依赖图（ExecutorSelector），
# 导致 MlKitInitProvider 在应用启动时抛 DependencyCycleException 闪退。
# 完整保留 ML Kit / Firebase Components，并关闭 R8 优化（fcitx5 已 -dontobfuscate）。
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * extends com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.firebase.**

# 关闭 R8 优化：避免第三方库（ML Kit / Firebase / Coil 等）内部单例与反射注册被破坏
-dontoptimize

# sherpa-onnx 语音识别引擎（进程内 SenseVoice）：保留其 JNI 绑定类，避免缩略后运行时 UnsatisfiedLinkError
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
