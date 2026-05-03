# llama.cpp Bridge
-keep class com.aicustomer.engine.LlmEngine { *; }

# Sherpa-ONNX
-keep class com.k2fsa.sherpa.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# JNI Bridge - 保留native方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 不要混淆引擎类
-keep class com.aicustomer.engine.** { *; }
-keep class com.aicustomer.data.model.** { *; }
