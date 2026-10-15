# Keep all classes - only remove unused code, don't obfuscate
-dontobfuscate
-dontoptimize

# Keep all app classes
-keep class com.jepongdevxyz.remote.** { *; }

# Keep WebRTC (required for video calls)
-keep class org.webrtc.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep ZXing
-keep class com.google.zxing.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
