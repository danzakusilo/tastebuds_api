# syntax=docker/dockerfile:1.7
# Build the jar first: ./gradlew :services:core:bootJar
# Then: docker build -t tastebuds-core services/core

FROM eclipse-temurin:21-jre AS extract
WORKDIR /build
COPY build/libs/core-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Distroless: no shell, no package manager, non-root by default.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER nonroot:nonroot
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
