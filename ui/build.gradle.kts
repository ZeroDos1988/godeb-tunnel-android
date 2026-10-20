@file:Suppress("UnstableApiUsage")

import java.util.Properties

val pkg: String = providers.gradleProperty("wireguardPackageName").get()
val appId: String = providers.gradleProperty("wireguardApplicationId").getOrElse(pkg)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

// Release signing (GoDeb): read from a keystore.properties outside the
// repository, default ~/.config/godeb-android/keystore.properties, or the path
// in -PgodebKeystoreProperties / GODEB_KEYSTORE_PROPERTIES. Without it the
// release build stays unsigned, as upstream.
val godebSigning: Properties? = run {
    val path = providers.gradleProperty("godebKeystoreProperties").orNull
        ?: System.getenv("GODEB_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/godeb-android/keystore.properties"
    val file = File(path)
    if (!file.isFile) return@run null
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    compileSdk = 36
    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
    }
    namespace = pkg
    defaultConfig {
        applicationId = appId
        minSdk = 24
        versionCode = providers.gradleProperty("wireguardVersionCode").get().toInt()
        versionName = providers.gradleProperty("wireguardVersionName").get()
        buildConfigField("int", "MIN_SDK_VERSION", minSdk.toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        godebSigning?.let { props ->
            create("godeb") {
                storeFile = File(props.getProperty("storeFile"))
                val password = File(props.getProperty("storePasswordFile")).readText().trim()
                storePassword = password
                keyAlias = props.getProperty("keyAlias")
                keyPassword = password
            }
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("godeb")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-android-optimize.txt")
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("googleplay") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "LongLogTag"
        warning += "MissingTranslation"
        warning += "ImpliedQuantity"
    }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}
