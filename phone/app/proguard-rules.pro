# kotlinx.serialization: сериализаторы моделей
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class kz.damird.diktofon.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kz.damird.diktofon.**$$serializer { *; }
-keepclasseswithmembers class kz.damird.diktofon.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp — платформенные заглушки
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
