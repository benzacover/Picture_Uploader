# Add project specific ProGuard rules here.
# Keep Google API client classes
-keep class com.google.api.** { *; }
-keep class com.google.http.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.http.**
