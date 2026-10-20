import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.gold.chatapp"
    //noinspection GradleDependency
    compileSdk = 36

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storePassword = keystoreProperties["storePassword"] as String?
            // Resolve keystore file relative to root project safely
            val path = keystoreProperties["storeFile"] as String?
            if (path != null) {
                storeFile = rootProject.file(path.removePrefix("../"))
            }
        }
    }

    defaultConfig {
        applicationId = "com.gold.chatapp"
        minSdk = 23
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/resources/annotations/errorprone/annotations/ErrorCode.gwt.xml"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    configurations.all {
        resolutionStrategy {
            force(libs.grpc.android)
            force(libs.grpc.okhttp)
            force(libs.grpc.stub)
            force(libs.grpc.protobuf.lite)
            force(libs.grpc.core)
            force(libs.grpc.api)
            force(libs.grpc.context)
        }
    }
}

dependencies {
    // AndroidX Core & UI
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.constraintlayout)

    // Circular Image View & WebRTC
    implementation(libs.circleimageview)
    implementation(libs.webrtc)

    // Lifecycle & Activity Extensions
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity)

    // Firebase (Using BOM for version alignment)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.storage)

    // Add Play Services Auth specifically to help with real-device package recognition
    implementation(libs.play.services.auth)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Media3 (Voice Messages)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Glide for Images
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.json)
    implementation(libs.socket.io.client)

    // Google Auth Library
    implementation(libs.google.auth.library) {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
        exclude(group = "io.grpc")
    }

    // Explicit gRPC for Firestore stability
    implementation(libs.grpc.android)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.core)
    implementation(libs.grpc.api)
    implementation(libs.grpc.context)

    // --- THE MISSING LINK ---
    // Required to prevent the "IllegalArgumentException" when reading the JSON key
    implementation(libs.jackson)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
