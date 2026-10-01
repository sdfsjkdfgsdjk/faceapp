plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.faceswapjoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.faceswapjoke"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем debug-ключом, чтобы release-APK можно было сразу поставить на телефон
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // Модель нейросети читается напрямую из APK — её нельзя сжимать
        noCompress += "tflite"
    }
}

// Нейросеть MediaPipe для поиска волос и лица (≈16 МБ) — скачивается при первой сборке
val modelFile = file("src/main/assets/selfie_multiclass_256x256.tflite")
val downloadModel by tasks.registering {
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists() || modelFile.length() < 1000) {
            modelFile.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/image_segmenter/" +
                "selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
            logger.lifecycle("Скачиваю модель: $url")
            val part = File(modelFile.path + ".part")
               uri(url).toURL().openStream().use { input ->
part.outputStream().use { input.copyTo(it) }
            }
            modelFile.delete()
            check(part.renameTo(modelFile)) { "Не удалось сохранить модель" }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    val cameraX = "1.4.2"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    // Модель распознавания лиц встроена в APK — работает без интернета
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Нейросеть сегментации волос/лица
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
