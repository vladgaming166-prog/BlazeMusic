# BlazeMuzix release shrinking rules.

# Keep Glide generated/annotated classes
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }
-dontwarn com.bumptech.glide.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Preserve line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Models parsed through org.json use explicit code, nothing reflective to keep.
