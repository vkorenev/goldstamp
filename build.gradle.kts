plugins {
    base
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

spotless {
    java {
        target("**/src/**/*.java")
        targetExclude("**/build/**", "**/.gradle/**")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
    }
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
}
