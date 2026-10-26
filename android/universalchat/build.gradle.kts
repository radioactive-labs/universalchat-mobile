plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
    id("com.vanniktech.maven.publish")
}

group = "com.universalchatbot"
version = "0.9.0"

android {
    namespace = "com.universalchatbot.chat"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// What the Flutter and React Native wrappers, and apps, depend on. Publishing
// to Maven Central reads mavenCentralUsername, mavenCentralPassword and the
// signingInMemoryKey* properties from ~/.gradle/gradle.properties or the
// ORG_GRADLE_PROJECT_ environment variables. Without a signing key it still
// publishes to the local repository; Maven Central refuses unsigned uploads.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

    coordinates("com.universalchatbot", "universalchat", version.toString())

    pom {
        name.set("UniversalChat")
        description.set("The UniversalChatbot chat as a native Jetpack Compose screen, with signed-in users, visitor attributes and push.")
        inceptionYear.set("2026")
        url.set("https://universalchatbot.com")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("fayaworks")
                name.set("Fayaworks Technologies")
                organization.set("Fayaworks Technologies")
            }
        }
        scm {
            url.set("https://github.com/radioactive-labs/universalchat-mobile")
            connection.set("scm:git:git://github.com/radioactive-labs/universalchat-mobile.git")
            developerConnection.set("scm:git:ssh://git@github.com/radioactive-labs/universalchat-mobile.git")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
