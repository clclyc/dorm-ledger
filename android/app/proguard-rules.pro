# 保留通过 @JavascriptInterface 暴露给网页的原生桥接方法，
# 防止将来开启混淆（minifyEnabled true）时被裁掉导致导出 / 复制失效。
-keepclassmembers class com.dorm.ledger.MainActivity$Bridge {
    public *;
}
-keepattributes JavascriptInterface