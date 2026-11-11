plugins {
    id("buildlogic.java-common-conventions")
    `java-library`
}

dependencies {
    api(project(":jsonpath"))
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)

    // JsonPath logs through SLF4J; bind a provider for tests only.
    testRuntimeOnly(platform(libs.slf4j.bom))
    testRuntimeOnly(libs.slf4j.simple)
}
