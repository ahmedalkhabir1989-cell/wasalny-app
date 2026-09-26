plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wasalny.sidisalem"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wasalny.sidisalem"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "4.1.1"

        // اقرأ مفتاح الخرائط من Secrets / env / gradle.properties
        val mapsKey = providers.gradleProperty("MAPS_API_KEY")
            .orElse(providers.environmentVariable("MAPS_API_KEY"))
            .orElse("YOUR_GOOGLE_MAPS_API_KEY_HERE")
            .get()
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey

        // لينك السيرفر — غيّره هنا أو عبر -PAPI_BASE=...
        val apiBase = providers.gradleProperty("API_BASE")
            .orElse(providers.environmentVariable("API_BASE"))
            .orElse("https://wasalny-sidi-salem.onrender.com")
            .get()
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}
