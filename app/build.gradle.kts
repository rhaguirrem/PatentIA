import java.io.File
import java.util.Properties
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { 
        localProperties.load(it)
    }
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use {
        keystoreProperties.load(it)
    }
}

fun readConfig(name: String, fallback: String? = null): String? {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: fallback
}

val mapsApiKey = readConfig("MAPS_API_KEY", "REPLACE_WITH_MAPS_API_KEY") ?: "REPLACE_WITH_MAPS_API_KEY"
val isMapsApiKeyConfigured = mapsApiKey.isNotBlank() && mapsApiKey != "REPLACE_WITH_MAPS_API_KEY"
val defaultAppUpdatePageUrl = "https://github.com/rhaguirrem/PatentIA/releases/latest"
val defaultAppUpdateApkUrl = "https://github.com/rhaguirrem/PatentIA/releases/latest/download/patentia-installer-release.apk"
val defaultAppUpdateManifestUrl = "https://github.com/rhaguirrem/PatentIA/releases/latest/download/app-update-manifest.json"
val appUpdatePageUrl = readConfig("APP_UPDATE_PAGE_URL", defaultAppUpdatePageUrl) ?: defaultAppUpdatePageUrl
val appUpdateApkUrl = readConfig("APP_UPDATE_APK_URL", defaultAppUpdateApkUrl) ?: defaultAppUpdateApkUrl
val appUpdateManifestUrl = readConfig("APP_UPDATE_MANIFEST_URL", defaultAppUpdateManifestUrl) ?: defaultAppUpdateManifestUrl
val releaseKeystorePath = readConfig("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = readConfig("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = readConfig("ANDROID_KEY_ALIAS")
val releaseKeyPassword = readConfig("ANDROID_KEY_PASSWORD")
val releaseKeystoreFile = releaseKeystorePath?.let { configuredPath ->
    val candidate = File(configuredPath)
    if (candidate.isAbsolute) candidate else File(rootProject.projectDir, configuredPath)
}
val isReleaseSigningConfigured = listOf(
    releaseKeystoreFile?.path,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val appVersionCode = 3
val appVersionName = "0.1.2"
val apkArchiveDir = File("G:/Mi unidad/Projects/PatentIA")
val releaseApkFileName = "patentia-installer-release.apk"
val releaseApkArchiveFileName = "patentia-installer-release-$appVersionName.apk"
val releaseApkFile = layout.buildDirectory.file("outputs/apk/release/$releaseApkFileName")
val updateManifestFile = rootProject.file("docs/app-update-manifest.json")

android {
    namespace = "com.patentia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.patentia"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DEFAULT_FIRESTORE_GROUP_ID", "\"patentia-demo\"")
        buildConfigField("boolean", "IS_MAPS_API_KEY_CONFIGURED", isMapsApiKeyConfigured.toString())
        buildConfigField("String", "APP_UPDATE_PAGE_URL", "\"$appUpdatePageUrl\"")
        buildConfigField("String", "APP_UPDATE_APK_URL", "\"$appUpdateApkUrl\"")
        buildConfigField("String", "APP_UPDATE_MANIFEST_URL", "\"$appUpdateManifestUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    signingConfigs {
        create("release") {
            if (isReleaseSigningConfigured) {
                storeFile = requireNotNull(releaseKeystoreFile)
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

android.applicationVariants.configureEach {
    outputs.configureEach {
        val outputFileName = if (name == "release") {
            releaseApkFileName
        } else {
            "patentia-installer-$name.apk"
        }
        (this as ApkVariantOutputImpl).outputFileName = outputFileName
    }
}

val writeUpdateManifest by tasks.registering {
    doLast {
        val builtReleaseApk = releaseApkFile.get().asFile
        check(builtReleaseApk.exists()) {
            "Release APK not found at ${builtReleaseApk.absolutePath}"
        }
        val publishedAtEpochMillis = System.currentTimeMillis()

        val manifestJson = """
            {
              "packageName": "com.patentia",
              "versionName": "$appVersionName",
              "versionCode": $appVersionCode,
                            "publishedAtEpochMillis": $publishedAtEpochMillis,
              "apkUrl": "$appUpdateApkUrl",
              "pageUrl": "$appUpdatePageUrl",
              "fileSizeBytes": ${builtReleaseApk.length()}
            }
        """.trimIndent() + System.lineSeparator()

        updateManifestFile.parentFile.mkdirs()
        updateManifestFile.writeText(manifestJson)
    }
}

val copyReleaseArtifacts by tasks.registering(Copy::class) {
    dependsOn(writeUpdateManifest)
    from(releaseApkFile)
    from(releaseApkFile) {
        rename { releaseApkArchiveFileName }
    }
    from(updateManifestFile)
    into(apkArchiveDir)
    includeEmptyDirs = false
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseArtifacts)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.7.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(firebaseBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.guava:guava:33.2.1-android")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.maps.android:maps-compose:6.4.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
