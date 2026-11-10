import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Outil de construction du catalogue local de recettes :
//   ./gradlew -p tools/catalog run --args="build --site jow --out catalog --minutes 10"
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("fr.mescourses:core")
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

application {
    mainClass.set("fr.mescourses.tools.catalog.MainKt")
}
