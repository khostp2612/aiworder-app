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
                arguments += "-DGGML_VULKAN=OFF"
                arguments += "-DGGML_NATIVE=ON"
                arguments += "-DGGML_CPU_KLEIDIAI=OFF"
                arguments += "-DGGML_LLAMAFILE=ON"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_CCACHE=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                // Cross-compilation ARM feature flags for SD8G2 hybrid cores
                // All cores (X3+A715+A710+A510): dotprod ✅
                // Only X3+A715: i8mm ❌, SVE ❌ (crashes on A710/A510)
                arguments += "-DGGML_MACHINE_SUPPORTS_dotprod_EXITCODE=0"
                arguments += "-DGGML_MACHINE_SUPPORTS_dotprod_EXITCODE__TRYRUN_OUTPUT=0"
                arguments += "-DGGML_MACHINE_SUPPORTS_i8mm_EXITCODE=1"
                arguments += "-DGGML_MACHINE_SUPPORTS_i8mm_EXITCODE__TRYRUN_OUTPUT=1"
                arguments += "-DGGML_MACHINE_SUPPORTS_sve_EXITCODE=1"
                arguments += "-DGGML_MACHINE_SUPPORTS_sve_EXITCODE__TRYRUN_OUTPUT=1"
                // Preload CMake cache for cross-compilation ARM features
                arguments += "-C${file("../native/arm64-cache.cmake").absolutePath}"
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
            // libonnxruntime.so 由 sherpa-onnx AAR 内嵌版本提供（ORT ~1.24.x），
            // 不参与 pickFirsts 冲突，避免 Maven 旧版 .so 覆盖导致 JNI ABI 不兼容闪退。
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = false
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

    // ONNX Runtime — compileOnly: 只保留 Java API (EmbeddingEngine 需要 ai.onnxruntime.*)，
    // native libonnxruntime.so 由 sherpa-onnx AAR 内嵌版本提供，避免版本冲突。
    compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Sherpa-ONNX (STT/TTS/VAD Java API) - 本地 AAR 依赖 (v1.13.2)
    // curl -L -o app/libs/sherpa-onnx.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar
    implementation(files("libs/sherpa-onnx.aar"))

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}