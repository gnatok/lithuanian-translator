plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.gnatok.translator"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.gnatok.translator"; minSdk = 31; targetSdk = 36; versionCode = 6; versionName = "0.4.1-cleanup-fix"
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(12) ?: "local"}\"")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.meta.wearable:mwdat-core:1.0.0")
    implementation("com.meta.wearable:mwdat-camera:1.0.0")
    implementation("com.meta.wearable:mwdat-inputs:1.0.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
