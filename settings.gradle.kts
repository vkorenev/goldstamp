pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("io.github.ben-manes.versions") version "0.67.0"
}

rootProject.name = "goldstamp"
include("core")
include("jsonpath")
include("jackson3-json")
