import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module Kotlin/JVM pur : toute la logique métier (sans dépendance Android),
// testable sur n'importe quelle machine avec `./gradlew -p core test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "fr.mescourses"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.jsoup)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test { useJUnitPlatform() }
