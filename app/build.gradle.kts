import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fr.mescourses.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.mescourses.app"
        minSdk = 31
        targetSdk = 36
        // La CI passe le numéro de build (-PversionCode) : l'appli s'en sert pour savoir si une mise à jour existe.
        val build = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.3.$build"
        // LiteRT-LM ne fournit que des bibliothèques natives 64 bits ; le Magic 5 Pro est en arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Clé fixe fournie par la CI (secrets GitHub) : les mises à jour s'installent par-dessus sans perdre les données.
    val fixedStore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (fixedStore != null) create("fixe") {
            storeFile = file(fixedStore)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Application personnelle : clé fixe si disponible, sinon clé de debug pour l'installer directement.
            signingConfig = signingConfigs.getByName(if (fixedStore != null) "fixe" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        noCompress += listOf("gz", "litertlm")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("fr.mescourses:core")

    implementation(libs.litertlm.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.android)
    implementation(libs.ktor.client.cio)
}
