-keeppackagenames org.jsoup.nodes

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses

-keepclassmembers class com.RobinNotBad.BiliClient.model.** {
    <fields>;
    <init>(...);
}

# ---- JNI / 依赖反射的第三方库 ----
-keep class master.flame.danmaku.** { *; }
-keep class tv.danmaku.ijk.media.** { *; }
-keep class com.netease.hearttouch.brotlij.** { *; }

# EventBus：靠注解 + 反射分发订阅方法（官方规则）
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

# OkHttp / Okio
# 注意：这里仍是整包 keep。okhttp3.internal.platform.Platform 会用 Class.forName 探测
# Conscrypt / BouncyCastle，收窄后必须真机回归，审计 S10 未把 OkHttp 列入本次收窄范围，
# 故保持原样（若要继续减体积，这里是下一个可下手处）。
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keep class kotlin.coroutines.Continuation
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Glide（官方规则）
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# PhotoView
-keep class com.github.chrisbanes.photoview.** { *; }

# Protobuf 生成类（弹幕分段解析）
-keep class com.RobinNotBad.BiliClient.model.DmSegMobileReply { *; }
-keep class com.RobinNotBad.BiliClient.model.DanmakuElem { *; }

# 自定义 View：XML 里按类名反射实例化，构造器必须保留
-keep class com.RobinNotBad.BiliClient.ui.widget.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.RobinNotBad.BiliClient.BiliTerminal
-keep class com.RobinNotBad.BiliClient.ErrorCatch

# ---------------------------------------------------------------------------
# 审计 S10：以下三条整包 keep 已删除
#   -keep class com.RobinNotBad.BiliClient.activity.** { *; }
#   -keep class com.RobinNotBad.BiliClient.service.** { *; }
#   -keep class com.RobinNotBad.BiliClient.ui.** { *; }
# 删除理由：
#   1) 原注释声称 "keep names for Class.forName"，但全库 grep Class.forName 零命中，
#      理由不成立；
#   2) activity.** 含 PlayerActivity（3011 行）等最大的一批类，{ *; } 连方法体内联、
#      未调用分支裁剪都不允许，使末尾的 -optimizationpasses 5 形同虚设——这同时解释了
#      AGENTS.md 里"整轮死代码+死依赖清理只减约 0.22 MB"的反常数据：R8 本就没有可删空间；
#   3) 清单声明的组件与 XML layout 引用的类，AGP 会自动生成 keep 规则
#      （见 app/build/intermediates/aapt_proguard_file/*/aapt_rules.txt，实测 231 行），
#      Fragment 由 androidx.fragment 的 consumer rules 保留无参构造器并允许混淆，
#      Parcelable 的 CREATOR 亦由默认规则保留；自定义 View 另有上面 ui.widget 的兜底规则。
#
# 审计 M13-e：随依赖一同移除的死规则也已删除
#   Retrofit / kotlinx.serialization / Hilt / geetest(sensebot)。
# 另删除 -keep class ...BiliTerminalApp —— 该类从未被实例化（Manifest 的 Application
# 是 BiliTerminal），UETool 辅助方法已收拢进 BiliTerminal，源文件整体删除。
# ---------------------------------------------------------------------------

# R8 optimization passes
-optimizationpasses 5
# repackageclasses 与 mergeinterfacesaggressively 可能破坏运行时反射（EventBus/Glide），
# 首次开启 R8 暂禁用以降低风险，确认稳定后可恢复。
#-repackageclasses
#-allowaccessmodification
#-mergeinterfacesaggressively
