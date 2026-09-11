# MediaPipe Tasks uses reflection/JNI entry points that must remain available.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
