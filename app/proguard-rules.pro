# OneFera release shrinking rules.
# Firestore maps documents onto these data classes by reflection, so keep their members.
-keepclassmembers class com.onefera.app.data.model.** { *; }
-keep class com.onefera.app.data.model.** { <init>(); }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
