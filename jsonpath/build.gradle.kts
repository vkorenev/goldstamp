plugins {
    id("buildlogic.java-common-conventions")
    `java-library`
}

dependencies {
    api(project(":core"))
    api(libs.json.path)

    // JsonPath logs through SLF4J; bind a provider for tests only.
    testRuntimeOnly(platform(libs.slf4j.bom))
    testRuntimeOnly(libs.slf4j.simple)
}
