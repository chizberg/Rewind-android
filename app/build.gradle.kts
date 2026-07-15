plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    alias(libs.plugins.secrets)
}

android {
    namespace = "com.chizberg.rewind"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "com.chizberg.rewind"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

secrets {
    // Keys live in local.properties (gitignored). secrets.defaults.properties holds committed
    // placeholders so the project builds on a fresh checkout / CI without the real keys.
    // Each key is exposed as both a manifest placeholder (${MAPS_API_KEY}) and a BuildConfig
    // field (BuildConfig.GOOGLE_REST_API_KEY). Mirrors iOS Secrets.xcconfig -> Info.plist.
    defaultPropertiesFileName = "secrets.defaults.properties"
}

spotless {
    // Spotless defaults ktlint's editorConfigPath to the *subproject* dir, so the root
    // .editorconfig (shared with the IDE and detekt) was ignored. Point at it explicitly.
    // Standard props (indent, max_line_length, code style) then come from the file, but Spotless
    // does NOT forward per-rule enable/disable toggles from .editorconfig — those must go through
    // editorConfigOverride (known diffplug/spotless behavior). Naming is left to detekt (which
    // exempts @Composable by default); mirrors iOS swiftlint disabling identifier_name/type_name.
    val editorConfig = "$rootDir/.editorconfig"
    val ktlintOverrides = mapOf("ktlint_standard_function-naming" to "disabled")
    kotlin {
        target("src/**/*.kt")
        ktlint().setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint().setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.play.services.maps)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
