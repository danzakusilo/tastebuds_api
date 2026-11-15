plugins {
    id("tastebuds.coverage-aggregate")
}

dependencies {
    kover(project(":libs:observability"))
    kover(project(":services:core"))
}
