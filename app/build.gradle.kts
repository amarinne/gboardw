import java.util.Properties

plugins {
 id("com.android.application")
 kotlin("android")
}
val keystoreProps = Properties()
android {
 namespace = "vn.io.eza.gboardw"
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
  applicationId = "vn.io.eza.gboardw"
  minSdk = 31
  targetSdk = 36
  versionCode = 5
  versionName = "0.1.4"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 compileOnly("de.robv.android.xposed:api:82")
 testImplementation("junit:junit:4.13.2")
}
