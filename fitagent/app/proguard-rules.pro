-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# Gson maps JSON by field name: keep the network DTOs intact.
-keep class com.fitagent.data.remote.dto.** { *; }
# Retrofit service interfaces with suspend functions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
