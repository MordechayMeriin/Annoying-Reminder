# kotlinx.serialization keeps what it needs through its own bundled rules.
# OkHttp ships its own rules too. Keep line numbers so crash reports in the Play Console are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Credential Manager finds its Google Play services provider by reflection (rule from the androidx.credentials docs).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
