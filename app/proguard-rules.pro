# Add project specific ProGuard rules here.

# ML Kit models are referenced reflectively; keep them.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
