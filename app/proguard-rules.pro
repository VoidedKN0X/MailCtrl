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

# Bouncy Castle (OpenPGP). MailCtrl only uses its lightweight API (org.bouncycastle.openpgp, bcpg, crypto), which
# loads nothing by name, so R8 can shrink it like any other code. Its JCA provider and X.509 parts aren't used.
-dontwarn org.bouncycastle.**

# WorkManager keeps its job list in a Room 2.6.1 database, and Room creates the generated
# WorkDatabase_Impl by reflection through its no-argument constructor. Room 2.6.1's own rule
# (-keep class * extends androidx.room.RoomDatabase) names no members, and the R8 in Android
# Gradle Plugin 9 no longer keeps a constructor for such a rule, so without this line the app
# would crash at start (NoSuchMethodException: WorkDatabase_Impl.<init>). Same rule Room 2.7 ships.
-keep class * extends androidx.room.RoomDatabase { void <init>(); }

# WorkManager makes MailCtrl's workers (mail checks, notification actions, Send later, Snooze) by class name
# through this constructor. Named with its members, as the R8 in Android Gradle Plugin 9 wants it.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
