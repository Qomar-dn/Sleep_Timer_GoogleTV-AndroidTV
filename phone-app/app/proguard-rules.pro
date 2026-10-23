# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard configuration.

# Keep the TvApiClient inner class for JSON parsing
-keep class com.devanka.sleepremote.TvApiClient$StatusResponse { *; }
