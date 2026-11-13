# kotlinx.serialization models (library ships its own rules; these keep our DTOs and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.mailctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.mailctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.mailctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}


# JavaMail (android-mail) finds its IMAP and SMTP providers and MIME handlers by class name.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-keepattributes InnerClasses, EnclosingMethod
# Desktop Java classes JavaMail can use but never needs on Android.
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.naming.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-dontwarn myjava.awt.datatransfer.**

# No debug, verbose or info logging in release builds (MailCtrl's own code doesn't log; this also silences
# libraries).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
