import java.util.Properties

plugins {
 id("com.android.application")
 kotlin("android")
}
val keystoreProps = Properties()
android {
 namespace = "com.ez.gboardw"
 compileSdk = 36
 val keystoreFile = rootProject.file("keystore.properties")
 if (keystoreFile.exists()) keystoreProps.load(keystoreFile.inputStream())
 signingConfigs {
  create("release") {
   if (keystoreFile.exists()) {
    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
    storePassword = keystoreProps.getProperty("storePassword")
    keyAlias = keystoreProps.getProperty("keyAlias")
    keyPassword = keystoreProps.getProperty("keyPassword")
   }
  }
 }
 buildTypes {
  release {
   signingConfig = signingConfigs.getByName(if (keystoreFile.exists()) "release" else "debug")
   isMinifyEnabled = false
  }
 }
 defaultConfig {
  applicationId = "com.ez.gboardw"
  minSdk = 31
  targetSdk = 36
   versionCode = 8
   versionName = "0.1.7"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
  // DexKit (playbook: zalo-patch DexKitBridgeRunner isolation + fingerprint +
  // preflight): upstream LuckyPray/DexKit 2.2.0, Maven Central
  // org.luckypray:dexkit:2.2.0 (AAR). Kotlin bindings are Apache-2.0; native
  // Core/ is LGPL-3.0 and ships unmodified inside this APK, loaded via
  // System.loadLibrary (dynamic link). Notices live in assets/dexkit-notices.txt.
  implementation("org.luckypray:dexkit:2.2.0")
  compileOnly("de.robv.android.xposed:api:82")
 testImplementation("junit:junit:4.13.2")
}
