plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.llm"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.llm"
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
        // GGUF 模型体积大且本身已量化压缩，跳过 AAPT2 二次压缩以加快打包，
        // 并便于首次加载时以流方式读出后拷贝到 filesDir。
        @Suppress("UnstableApiUsage")
        noCompress += "gguf"
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
