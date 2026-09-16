plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.kuddev.pebrel.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.kuddev.pebrel.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].assets.srcDir("build/generated/pebrelAssets")
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

val generateAssets by tasks.registering(Exec::class) {
    val root = rootProject.projectDir.resolve("../..").canonicalFile
    inputs.files(root.resolve("nebula_settings/src/lib.rs"), root.resolve("nebula_settings/src/themes.rs"),
        root.resolve("nebula_app/src/display/ui/os_icons.rs"), root.resolve("assets/fonts/MapleMonoNormal-NF-CN-Regular.ttf"))
    inputs.dir(root.resolve("mobile/tools"))
    inputs.dir(root.resolve("mobile/android/third_party/licenses"))
    inputs.files(root.resolve("mobile/android/third_party/THIRD-PARTY-NOTICES.md"),
        root.resolve("mobile/android/third_party/termux/LICENSE.md"),
        root.resolve("mobile/android/third_party/termux/UPSTREAM.md"))
    outputs.dir(layout.buildDirectory.dir("generated/pebrelAssets"))
    commandLine("python3", root.resolve("mobile/tools/generate_assets.py"),
        "--output", layout.buildDirectory.dir("generated/pebrelAssets").get().asFile)
}
tasks.named("preBuild") { dependsOn(generateAssets) }
