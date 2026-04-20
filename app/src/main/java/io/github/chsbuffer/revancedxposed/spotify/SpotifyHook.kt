package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {
    override val hooks = arrayOf(
        ::SanitizeSharingLinks,
        ::FixThirdPartyLaunchersWidgets,
    )
}

