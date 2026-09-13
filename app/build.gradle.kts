plugins {
 id("com.android.application")
 kotlin("android")
}
android {
 namespace = "vn.io.eza.gboardw"
 compileSdk = 36
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
