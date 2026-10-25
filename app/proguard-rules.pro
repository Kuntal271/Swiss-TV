# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.swissdark.launcher.**$$serializer { *; }
-keepclassmembers class com.swissdark.launcher.** { *** Companion; }
-keepclasseswithmembers class com.swissdark.launcher.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
