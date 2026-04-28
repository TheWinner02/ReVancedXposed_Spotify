package io.github.chsbuffer.revancedxposed.spotify.misc.widgets

import io.github.chsbuffer.revancedxposed.ChimeraBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.FixThirdPartyLaunchersWidgets() {
    ::canBindAppWidgetPermissionFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
            param.setResult(true)
        }
    })
}