package io.github.chsbuffer.revancedxposed

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.ArgumentsSource
import org.luckypray.dexkit.DexKitBridge
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

@ParameterizedClass
@ArgumentsSource(FilePathArgumentsProvider::class)
class FingerprintsKtTest(val apkPath: Path) {
    init {
        TestSetup.setupForApk(apkPath.toString())
    }

    val dexkit: DexKitBridge = TestSetup.dexkit.get()!!
    val appVersion: AppVersion = TestSetup.appVersion.get()!!

    fun testFingerprints(app: String, clazz: Class<*>) {
        val errors = mutableListOf<Throwable>()
        clazz.methods.asSequence()
            .filter { it.isStatic }
            .filter { !it.isAnnotationPresent(SkipTest::class.java) }
            .forEach { method ->
                val methodName = method.name.drop(3)
                method.getAnnotation(RequireAppVersion::class.java)?.also { anno ->
                    try {
                        match(appVersion, anno.minVersion, anno.maxVersion)
                    } catch (e: VersionConstraintFailedException) {
                        print("$methodName: ")
                        System.out.flush()
                        System.err.println("Skipping: ${e.message}")
                        return@forEach
                    }
                }

                method.getAnnotation(TargetApp::class.java)?.also { anno ->
                    if (anno.app != app) return@forEach
                }

                print("$methodName: ")
                try {
                    val func = method(null) as? FindFunc ?: return@forEach
                    val time = measureTimeMillis {
                        val value = func(dexkit)
                        if (value is List<*>) {
                            assertTrue(value.isNotEmpty())
                            print(value.joinToString(", "))
                        } else {
                            print("$value")
                        }
                    }
                    if (time > 20) {
                        print(", slow match: ${time}ms")
                    }
                    println()
                } catch (e: Throwable) {
                    println()
                    errors.add(e)
                    System.err.println(e.stackTraceToString())
                }
            }

        if (errors.isNotEmpty())
            throw AssertionError()
    }

    @TestFactory
    fun fingerprintTest(): Iterator<DynamicTest> = sequence {
        val app = when {
            apkPath.name.startsWith("com.spotify.music") -> "spotify"
            else -> return@sequence
        }

        val classLoader = Thread.currentThread().contextClassLoader!!

        val fingerprintClassList =
            Files.walk(Path("src/main/java/io/github/chsbuffer/revancedxposed/$app"))
                .filter { Files.isRegularFile(it) && it.fileName.toString() == "Fingerprints.kt" }
                .map {
                    // drop src/main/java
                    it.invariantSeparatorsPathString.split("/").drop(3)
                        // convert to class name
                        .joinToString(".").replace(".kt", "Kt")
                }.toList().toMutableList()

        fingerprintClassList.addAll(SharedFingerprintsProvider.getSharedFingerprints(app))

        fingerprintClassList.forEach {
            val clz = classLoader.loadClass(it)
            val category = it.split(".").drop(5).joinToString(".") // drop your.patches.app prefix
            yield(DynamicTest.dynamicTest(category) { testFingerprints(app, clz) })
        }
    }.iterator()
}