# SPDX-License-Identifier: GPL-3.0-or-later
# Hilt, Room, Compose and the AndroidX libraries ship their own consumer rules.

# kotlinx.serialization: keep the generated serializers of @Serializable navigation routes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.tandem.** {
    *** Companion;
}
-keepclasseswithmembers class app.tandem.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The listener service is bound by name from the system.
-keep class app.tandem.plugins.notifications.TandemNotificationListener

# Apache MINA SSHD (the SFTP server). It loads ciphers, key exchanges and subsystems by name, so keep it whole.
-keep class org.apache.sshd.** { *; }
-keep class org.slf4j.** { *; }
# Optional pieces it probes for and handles when they are missing.
-dontwarn javax.management.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.apache.tomcat.jni.**
-dontwarn org.slf4j.**
# Kerberos, JAAS and RMI do not exist on Android. The server never reaches the code that uses them.
-dontwarn java.rmi.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
