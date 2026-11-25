# Keep built-in privileged input server, Shizuku UserService, AIDL, and HiddenApiBypass
-keep class com.example.server.PadForgeInputServer { *; }
-keep class com.example.InputUserService { *; }
-keep class com.example.IInputService { *; }
-keep class com.example.IInputService$** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-keep class org.conscrypt.** { *; }

