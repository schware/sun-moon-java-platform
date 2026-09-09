# Build stage — full JDK, produces build/install/<name>/{bin,lib}
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Wrapper + build scripts first, so dependency resolution is cached
# independently of source edits.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
# The wrapper is 755 in git, but a build context copied from a Windows
# checkout loses the bit — set it explicitly rather than depend on the host.
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

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

# BO is the published surface (docs/adr/0007); the Order API and Socket
# ports stay internal unless explicitly published.
EXPOSE 8080

ENTRYPOINT ["./bin/sun-moon-java-platform"]
