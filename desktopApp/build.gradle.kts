import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    // Bundles Skiko native binaries for the OS running the build
    implementation(compose.desktop.currentOs)
    // Provides Dispatchers.Main on the Swing event thread
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.doronyg.photoarchivededuplicator.MainKt"
        // Skiko loads native libraries; newer JDKs warn (and will later block) without this
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // The bundled runtime is trimmed with jlink; java.sql is required by the SQLite JDBC driver.
            // Re-check with ./gradlew :desktopApp:suggestRuntimeModules after adding dependencies.
            modules("java.instrument", "java.sql", "jdk.unsupported")
            packageName = "PhotoArchiveDeduplicator"
            packageVersion = "1.0.0"
        }
    }
}
