plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Optional, machine-local: `wakeforce.buildRoot=<dir>` in local.properties moves build output
// out of cloud-synced folders (OneDrive/Dropbox lock files mid-build and break Gradle).
val localProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
localProperties.getProperty("wakeforce.buildRoot")?.let { root ->
    allprojects { layout.buildDirectory.set(file("$root/$name")) }
}
