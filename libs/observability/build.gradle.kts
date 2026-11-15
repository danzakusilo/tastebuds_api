plugins {
    id("tastebuds.spring-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Reusable observability building blocks, auto-configured into any Spring Boot service that depends on this module.
dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation(libs.findLibrary("otel-logback-appender").get())
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}
