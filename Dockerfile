# Build stage — full JDK, produces build/install/<name>/{bin,lib}
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Wrapper + build scripts first, so dependency resolution is cached
# independently of source edits.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY core/gradle ./core/gradle
COPY core/build.gradle.kts core/settings.gradle.kts ./core/
# The wrapper is 755 in git, but a build context copied from a Windows
# checkout loses the bit — set it explicitly rather than depend on the host.
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY core/src ./core/src
COPY src ./src
# Tests run in CI/locally, not in the image build: the live-HTTP tests bind
# real ports, which is a poor fit for a sandboxed build step.
RUN ./gradlew --no-daemon installDist -x test

# Runtime stage — JRE only, no Gradle, no sources
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin platform
COPY --from=build --chown=platform:platform /build/build/install/sun-moon-java-platform ./
USER platform

# This runtime's two slots in the family-wide port scheme (docs/adr/0013):
# the REST/WebSocket API and the raw Socket transport. BO is no longer here
# — it is its own image on 8080 (docs/adr/0014).
EXPOSE 8083 9011

ENTRYPOINT ["./bin/sun-moon-java-platform"]
