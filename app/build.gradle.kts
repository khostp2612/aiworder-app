plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aicustomer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aicustomer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // 只构建arm64-v8a（手机端），减少编译时间
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_CPU_KLEIDIAI=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_CCACHE=OFF"
                arguments += "-DLLAMA_CURL=OFF"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = true  // 兼容 HarmonyOS，确保 extractNativeLibs 生效
        }
    }

    // 不压缩gguf/onnx/txt模型文件，避免assets提取时额外解压开销
    aaptOptions {
        noCompress("gguf", "onnx", "txt")
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Room (使用KSP替代annotationProcessor)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Sherpa-ONNX (STT/TTS/VAD Java API) - 本地AAR依赖
    // 需要先下载AAR到 app/libs/ 目录：
    // curl -L -o app/libs/sherpa-onnx.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.40/sherpa-onnx-1.12.40.aar
    implementation(files("libs/sherpa-onnx.aar"))

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Encrypted SharedPreferences for API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // OkHttp for Xfyun cloud ASR WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
}
