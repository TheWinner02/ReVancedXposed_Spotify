package io.github.chsbuffer.revancedxposed

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.security.JadxSecurityFlag
import jadx.api.security.impl.JadxSecurity
import jadx.core.utils.android.AndroidManifestParser
import jadx.core.utils.android.AppAttribute
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.EnumSet

object TestSetup {
    var lastApkPath = ThreadLocal<String>()
    val dexkit = ThreadLocal<DexKitBridge>()
    val jadx = ThreadLocal<JadxDecompiler>()
    val jadxResourceReader = ThreadLocal<JadxResourceReader>()
    val appVersion = ThreadLocal<AppVersion>()

    init {
        resourceMappings = object : ResourceFinder {
            override operator fun get(type: String, name: String): Int =
                jadxResourceReader.get()!![type, name]
        }
    }

    private fun setupDexKit(apkPath: String) {
        try {
            System.loadLibrary("dexkit")
        } catch (_: UnsatisfiedLinkError) {
            System.loadLibrary("libdexkit")
        }

        dexkit.set(DexKitBridge.create(apkPath))
    }

    private fun setupJadx(apkPath: String) {
        val jadxArgs = JadxArgs().apply {
            setInputFile(File(apkPath))
            security = JadxSecurity(JadxSecurityFlag.none())
        }
        val jadx = JadxDecompiler(jadxArgs)
        jadx.load()
        this.jadx.set(jadx)

        this.jadxResourceReader.set(JadxResourceReader(jadx))
        this.appVersion.set(jadx.getAppVersion())
    }

    private fun JadxDecompiler.getAppVersion(): AppVersion {
        val manifest = AndroidManifestParser(
            AndroidManifestParser.getAndroidManifest(resources),
            EnumSet.of(AppAttribute.VERSION_NAME),
            JadxSecurity(JadxSecurityFlag.none())
        )
        return AppVersion(manifest.parse().versionName)
    }

    fun setupForApk(apkPath: String) {
        if (lastApkPath.get() == apkPath) return

        setupDexKit(apkPath)
        setupJadx(apkPath)
        lastApkPath.set(apkPath)
    }
}