package io.github.chsbuffer.revancedxposed.spotify.misc.privacy

import android.content.ClipData
import app.revanced.extension.spotify.misc.privacy.SanitizeSharingLinksPatch
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.SanitizeSharingLinks() {
    ::shareCopyUrlFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(outerParam: ChimeraBridge.MethodHookParam) {
             ChimeraBridge.hookMethod(
                XposedHelpers.findMethodExact(ClipData::class.java, "newPlainText", CharSequence::class.java, CharSequence::class.java),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        val url = param.args?.get(1) as? String ?: return
                        param.args?.set(1, SanitizeSharingLinksPatch.sanitizeSharingLink(url))
                    }
                }
            )
        }
    })

    ::formatAndroidShareSheetUrlFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val url = param.args?.get(1) as? String ?: return
            param.args?.set(1, SanitizeSharingLinksPatch.sanitizeSharingLink(url))
        }
    })
}
