# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# PDFBox Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.commons.**
-dontwarn org.bouncycastle.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
