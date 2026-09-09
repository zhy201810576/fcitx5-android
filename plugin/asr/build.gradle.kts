plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.asr"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.asr"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        // SenseVoice 模型体积大且本身已压缩，跳过 AAPT2 二次压缩以加快打包，
        // 并让 sherpa-onnx 能以 mmap（openFd）方式直接读取模型。
        @Suppress("UnstableApiUsage")
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
