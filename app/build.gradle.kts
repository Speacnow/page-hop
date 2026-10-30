plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pagehop.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pagehop.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = 73
        versionName = "0.9.1"

        ndk {
            // arm64-v8a：真机（iQOO Z10 Turbo）
            // x86_64：Android Studio 的模拟器（pdfium 的 AAR 里四种 ABI 都带）
            // 两个都打进来，APK 大约 15MB；只留 arm64 能省 5MB，但模拟器就跑不了
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // 让 Robolectric 能拿到真实资源与合并后的 Manifest
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 注意：pdfiumandroid 2.x 起的 AAR 只有 classes.jar、不再带 .so，装上去会 UnsatisfiedLinkError。
    // 1.0.34 是最后一个自带 libpdfium.so 的版本，别随手升级。
    implementation("io.legere:pdfiumandroid:1.0.34")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里用真的 org.json 实现，而不是 Android 的桩
    testImplementation("org.json:json:20240303")
    // 在 JVM 上跑真实 Android 界面，用来验证"首次启动不会崩"
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
