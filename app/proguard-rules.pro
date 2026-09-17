# Keep the WebView JS bridge surface (reflection by name).
-keepclassmembers class com.tapgem.app.ui.WidgetView$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
