-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep class com.voiceagent.oneplus13.**.models.** { *; }
-keep class com.voiceagent.oneplus13.core.ChatMessage { *; }
-keep class com.voiceagent.oneplus13.core.DeepSeekRequest { *; }
-keep class com.voiceagent.oneplus13.core.DeepSeekChoice { *; }
-keep class com.voiceagent.oneplus13.core.DeepSeekResponse { *; }
-keep class com.voiceagent.oneplus13.core.ClaudeRequest { *; }
-keep class com.voiceagent.oneplus13.core.ClaudeContentBlock { *; }
-keep class com.voiceagent.oneplus13.core.ClaudeResponse { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }
