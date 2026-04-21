package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.setObjectField
import io.github.chsbuffer.revancedxposed.getObjectField

fun SpotifyHook.ForceIosLogin() {
    // 1. Spoof Client ID and User-Agent in ApplicationScopeConfiguration
    runCatching {
        ::setClientIdFingerprint.hookMethod {
            before { param ->
                param.args[0] = "58bd3c95768941ea9eb4350aaa033eb3"
                XposedBridge.log("ForceIosLogin: Spoofed Client ID to iOS value via Fingerprint")
            }
        }
        ::setUserAgentFingerprint.hookMethod {
            before { param ->
                param.args[0] = "iphone-9.0.58.558.g200011c iOS/17.7.2 (iPhone16,1)"
                XposedBridge.log("ForceIosLogin: Spoofed Default HTTP User-Agent via Fingerprint")
            }
        }
    }.onFailure {
        XposedBridge.log("ForceIosLogin ApplicationScopeConfiguration fingerprint error: ${it.message}")
    }

    // 2. Disable Integrity Verification
    runCatching {
        ::runIntegrityVerificationFingerprint.hookMethod {
            replace { param -> 
                param.result = null 
            }
        }
    }

    // 2. Spoof Orbit JNI Native Logic
    runCatching {
        ::orbitLibraryFingerprint.hookMethod {
            after {
                XposedBridge.log("ForceIosLogin: orbitLibraryFingerprint matched, potentially launching listener if needed")
                // ReVanced Patcher spoofed a local listener to intercept native requests.
                // For Xposed, we can hook the native HTTP stack directly as we already do.
            }
        }
    }

    // 3. Spoof Platform in Application/Client Info
    runCatching {
        ::loginClientPlatformFingerprint.hookMethod {
            before { param ->
                val args = param.args ?: return@before
                val arg = args.getOrNull(0) ?: return@before
                
                if (arg is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = arg as MutableMap<String, Any?>
                    if (map["platform"] == "android") {
                        map["platform"] = "ios"
                        XposedBridge.log("ForceIosLogin: Spoofed platform in Map to ios")
                    }
                } else {
                    runCatching {
                        // Use safe cast and null check before accessing javaClass
                        val clazz = arg?.javaClass ?: return@runCatching
                        
                        // Only attempt to spoof if the class name looks like a Client/App info class
                        // or if we're reasonably sure it's the target.
                        clazz.declaredFields.forEach { field ->
                            field.isAccessible = true
                            if (field.type == String::class.java) {
                                val value = field.get(arg) as? String
                                if (value == "android") {
                                    field.set(arg, "ios")
                                    XposedBridge.log("ForceIosLogin: Spoofed field ${field.name} in ${clazz.name} to ios")
                                }
                            }
                        }
                        // Specific protobuf field names as fallback
                        runCatching { arg.setObjectField("platform_", "ios") }
                        runCatching { arg.setObjectField("os_name", "ios") }
                    }.onFailure { 
                        // Silent failure for individual object spoofing
                    }
                }
            }
        }
    }.onFailure {
        XposedBridge.log("ForceIosLogin ClientInfo hook failed (Fingerprint may be missing): ${it.message}")
    }

    // 4. Disable Integrity Verification (Handled via other means if fingerprint is complex)
}
