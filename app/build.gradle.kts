import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 34
    flavorDimensions += "version"

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Curbox")
    }

    productFlavors {
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"
            buildConfigField("Boolean", "FDROID_VARIANT", "false")
            // Staging switch for the FCM push migration. While false, the realtime
            // websocket + poll stay on (no regression) and FCM only adds background
            // delivery. Flip to true once FCM is verified end to end: realtime is
            // then dropped and the poll slows right down, which is where the memory
            // and battery win lands.
            buildConfigField("Boolean", "SYNC_USE_FCM", "true")
        }
    }


    splits {
        abi {
            isEnable = false

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // required because of hardcoded f-droid values
            applicationVariants.all {
                val variant = this
                if (variant.flavorName == "fdroid") {
                    variant.outputs
                        .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                        .forEach { output ->
                            val outputFileName = "app-fdroid-universal-release-unsigned.apk"
                            println("OutputFileName: $outputFileName")
                            output.outputFileName = outputFileName
                        }
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Debug Curbox")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // QR Scanner & Generator
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Shizuku dependecies
    implementation (libs.api)
    implementation (libs.provider)

    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    // Cross device sync. These live only in the playstore flavor so the F-Droid
    // build compiles with no network, no auth, and no Supabase code at all.
    "playstoreImplementation"(libs.okhttp)
    "playstoreImplementation"(libs.androidx.security.crypto)
    "playstoreImplementation"(libs.androidx.work.runtime.ktx)

    // FCM push, Play Store flavor ONLY, so the phone can sync instantly in the
    // background without holding a websocket open. F-Droid never pulls in Firebase
    // or Google Play Services. Initialised manually from FcmConfig, so the
    // google-services Gradle plugin and google-services.json are NOT required.
    "playstoreImplementation"(platform("com.google.firebase:firebase-bom:33.5.1"))
    "playstoreImplementation"("com.google.firebase:firebase-messaging")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)
    implementation(libs.timerangepicker)

}
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        tasks.register("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs the app, grants Accessibility permission, and launches it"
            dependsOn("install$variantName")
            
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = variant.applicationId.get()
                Thread.sleep(2000)
                // Grant Accessibility Permission
                exec {
                    val baseId = "neth.iecal.curbox"
                    val combinedServices = "$appId/$baseId.services.AppBlockerService:$appId/$baseId.services.UsageTrackingService"

                    commandLine(adbPath, "shell", "settings", "put", "secure", "enabled_accessibility_services", combinedServices)
                }

                // Launch MainActivity
                exec {
                    val baseId = "neth.iecal.curbox"
                    commandLine(adbPath, "shell", "am", "start", "-n", "$appId/$baseId.ui.activity.FragmentActivity")
                }
            }
        }
    }
}
