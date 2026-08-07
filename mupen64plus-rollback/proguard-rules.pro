# Rollback Netcode ProGuard Rules
-keep class paulscode.mupen64plusae.rollback.** { *; }
-keepclassmembers class paulscode.mupen64plusae.rollback.** {
    native <methods>;
}
