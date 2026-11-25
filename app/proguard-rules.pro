# JS 桥接方法必须保留（即使将来开启混淆）
-keepclassmembers class xyz.ly77625.playground.MainActivity$Bridge {
    public *;
}
-keep class xyz.ly77625.playground.MainActivity { *; }
