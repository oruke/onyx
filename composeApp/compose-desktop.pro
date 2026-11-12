-dontwarn javax.servlet.**
-dontwarn jcifs.**
-dontwarn androidx.compose.material.**
-dontwarn com.ibm.security.**
-dontwarn com.intellij.openapi.**
-dontwarn com.sun.jna.**
-keep class * extends com.sun.jna.Structure {
    public <init>(...);
    <fields>;
}
-keep interface * extends com.sun.jna.Library {
    <methods>;
}
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn org.bouncycastle.**
