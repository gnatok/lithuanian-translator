plugins { id("com.android.application") }
android {
    namespace = "com.gnatok.translator"
    compileSdk = 36
    defaultConfig { applicationId = "com.gnatok.translator"; minSdk = 31; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
