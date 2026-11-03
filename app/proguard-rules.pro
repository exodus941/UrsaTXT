# The editor page calls these by name through the JavaScript bridge.
-keepclassmembers class com.ursatxt.EditorActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
