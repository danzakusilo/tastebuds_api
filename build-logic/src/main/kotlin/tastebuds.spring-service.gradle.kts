// Bootable Spring Boot service.
plugins {
    id("tastebuds.spring-library")
    id("org.springframework.boot")
}

springBoot {
    buildInfo()
}

// Only the executable jar is needed; the plain jar would be ambiguous for the Dockerfile's COPY.
tasks.named<Jar>("jar") {
    enabled = false
}
