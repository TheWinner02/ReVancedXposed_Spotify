import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Random

plugins {
    alias(libs.plugins.android.application)
    //alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

val gitCommitHashProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

val gitCommitDateProvider = providers.exec {
    commandLine("git log -1 --format=%cd --date=format:%y%m%d".split(' '))
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

private val seed = (project.properties["PACKAGE_NAME_SEED"] as? String ?: "0").toLong().also { println("Seed for package name: $it") }
private val myPackageName = genPackageName(seed).also { println("Package name: $it") }

private fun genPackageName(seed: Long): String {
    val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    val ALPHADOTS = "$ALPHA....."

    val random = Random(seed)
    val len = 5 + random.nextInt(15)
    val builder = StringBuilder(len)
    var next: Char
    var prev = 0.toChar()
    for (i in 0 until len) {
        next = if (prev == '.' || i == 0 || i == len - 1) {
            ALPHA[random.nextInt(ALPHA.length)]
        } else {
            ALPHADOTS[random.nextInt(ALPHADOTS.length)]
        }
        builder.append(next)
        prev = next
    }
    if (!builder.contains('.')) {
        // Pick a random index and set it as dot
        val idx = random.nextInt(len - 2)
        builder[idx + 1] = '.'
    }
    return builder.toString()
}

android {
    namespace = "io.github.chsbuffer.revancedxposed"

    defaultConfig {
        applicationId = myPackageName
        versionCode = 33
        versionName = gitCommitDateProvider.get().trim()
        buildConfigField("String", "COMMIT_HASH", "\"${gitCommitHashProvider.get().trim()}\"")
        
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    flavorDimensions += "abi"
    productFlavors {
        create("universal") {
            dimension = "abi"
        }
    }
    packaging.resources {
        excludes.addAll(
            arrayOf(
                "META-INF/**", "**.bin"
            )
        )
    }
    buildFeatures.buildConfig = true
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
            "-Xno-call-assertions"
        )
        jvmTarget = JvmTarget.JVM_17
    }
}
tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
//    implementation(libs.dexkit)
    implementation(group = "", name = "dexkit-android", ext = "aar")
    implementation(libs.flatbuffers.java) // dexkit dependency
    implementation(libs.annotation)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.material)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.jadx.core)
    testImplementation(libs.slf4j.simple)
    compileOnly(libs.xposed)
    compileOnly(project(":stub"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.resources.excludes.add("kotlin/**")
    }
}
