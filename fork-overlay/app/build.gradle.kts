// [Gradle 9移行] AGP 9.0+ の内蔵Kotlinサポートを使用（org.jetbrains.kotlin.android は不要）。
// Compose Compiler のみ独立プラグインとして明示適用（root build.gradle.kts でオーバーライドした
// Kotlin 2.3.21 と版数を一致させる）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.magi.app"
    // [Android 17 会話バブル] compileSdk は 36 のまま。当初 37 へ上げたが CI（Release Build）で
    //   sdkmanager が "Failed to find package 'platforms;android-37'" となり、API 37（Android 17）の
    //   platform SDK が未提供と確認（run 29385635188）。Bubbles API は Android 11/API30+ で minSdk 36 なら
    //   常時利用可のため、バブル機能は 36 で完全に動作する。API 37 の SDK 公開後に 36→37 と CI の
    //   sdkmanager 行へ platforms;android-37・build-tools;37.0.0 を追加すれば「Android 17 でコンパイル」へ移行できる。
    compileSdk = 36
    // [ネイティブ加速] NDK を明示固定（CI と開発環境で同一ビルドを保証。AGP の既定NDKに追従させない）。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // REBUILD_ENGINE: ShiftGfork は debug/release とも true（上流 V5 SA 経路を使わない）
        buildConfigField("Boolean", "REBUILD_ENGINE", "true")
        applicationId = "com.magi.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 530
        versionName = "3.370.0-load-saved-logs"
        // [ネイティブ加速] minSdk 36（Android 16+）の実機は arm64 のみ対象で十分。
        //   .so が無い環境でも NativeBridge が false を返し Kotlin パスで全機能が動く。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Personal-test release APK: signed with the debug key so it is installable from Actions.
            // Replace with a private release signingConfig before store distribution.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "REBUILD_ENGINE", "true")
            // No shrinking for this personal-test build. proguardFiles() is intentionally omitted:
            // it is ignored while isMinifyEnabled = false and only invites the false impression that
            // shrink rules are active. Re-add it together with isMinifyEnabled = true for a store build.
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
            // P3: デバッグは再構築エンジンを既定 ON（golden / 実機確認用）
            buildConfigField("Boolean", "REBUILD_ENGINE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // [Gradle 9移行] kotlinOptions{jvmTarget}は撤去。内蔵Kotlinは既定で
    // android.compileOptions.targetCompatibility(=17, 上記)からjvmTargetを継承するため明示不要
    // （公式ドキュメント確認: "You don't need to explicitly set jvmTarget... it defaults to
    // android.compileOptions.targetCompatibility"）。composeOptions.kotlinCompilerExtensionVersion は
    // Compose Compiler の独立プラグイン化(Kotlin 2.0+)で不要（版数は root build.gradle.kts の
    // org.jetbrains.kotlin.plugin.compose(2.3.21) が管理）。
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }

    // This release variant is a personal-test APK signed with the debug key (see buildTypes.release),
    // not a Play-store build. `lintVitalRelease` aborts the APK on any *fatal* lint issue, which only
    // blocks the test build without adding value here. Don't fail the build on lint; still emit the
    // HTML/XML report so issues remain inspectable in app/build/reports/.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 長時間の最適化計算をバックグラウンドで完遂させる（改善仕様書 §6 / §3.4）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath so StateParser (org.json) runs in JVM tests
    // (android.jar ships only throwing stubs). Used by the Web-golden parity test.
    testImplementation("org.json:json:20240303")
}

// Surface test stdout (the Web-golden breakdown comparison) in CI console logs.
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
