plugins { id("com.android.application") }
android {
    namespace = "com.gnatok.translator.wear"
    compileSdk = 36
    defaultConfig { applicationId = "com.gnatok.translator"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.google.android.gms:play-services-wearable:19.0.0") }
