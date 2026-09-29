# Contributing

Run `./gradlew build` before submitting changes. This builds the project, runs
tests, and checks formatting, as it does in CI.

## Formatting

Formatting uses Spotless with Google Java Format for Java and ktlint for Kotlin
sources and Gradle Kotlin scripts, including those in `build-logic`. Formatting
configuration lives in the root `build.gradle.kts`.

Run `./gradlew spotlessApply` from the repository root to format files, or
`./gradlew spotlessCheck` to check formatting without changing files.
