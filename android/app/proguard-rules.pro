# JavaMail 通过 ServiceLoader 加载协议实现，并引用部分 Android 缺失的 Java SE 类。
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class * implements javax.mail.Provider { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**

# 保留行号，崩溃堆栈可用 mapping.txt 还原。
-keepattributes SourceFile,LineNumberTable

# WorkManager / Room 通过反射构造数据库与 Worker，R8 全量裁剪会导致启动崩溃
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
