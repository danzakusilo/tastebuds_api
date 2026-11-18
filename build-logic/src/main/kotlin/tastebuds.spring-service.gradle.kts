// Bootable Spring Boot service.
plugins {
    id("tastebuds.spring-library")
    id("org.springframework.boot")
}

springBoot {
    // No timestamp: it would change build-info.properties on every build and defeat up-to-date checks and the cache.
    buildInfo { excludes.set(setOf("time")) }
}

// Only the executable jar is needed; the plain jar would be ambiguous for the Dockerfile's COPY.
tasks.named<Jar>("jar") {
    enabled = false
}
