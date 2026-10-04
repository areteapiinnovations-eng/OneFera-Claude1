# OneFera release shrinking rules.
# Firestore maps documents onto these data classes by reflection, so keep their members.
-keepclassmembers class com.onefera.app.data.model.** { *; }
-keep class com.onefera.app.data.model.** { <init>(); }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Razorpay Checkout (from Razorpay's Android integration guide).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-dontwarn com.razorpay.**
-keep class com.razorpay.** { *; }
-optimizations !method/inlining/*
-keepclasseswithmembers class * {
    public void onPayment*(...);
}
