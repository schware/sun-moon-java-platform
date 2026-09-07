plugins {
    java
    war
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.sunmoon.platform"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

// Spring/WAS/Jetty counterpart to the archived sun-moon-java-platform-netty.
// See docs/adr/0004-spring-was-jetty-replaces-netty.md for why.
//
// The WAR deliberately does NOT bundle its own SLF4J/Logback (see the
// configurations.all exclude below): the external Jetty container already
// binds org.slf4j as a shared/system class, and Spring Boot's own Logback
// LoggingSystem check fails with "LoggerFactory is not a Logback
// LoggerContext" if the WAR brings a second, classloader-isolated copy of
// Logback that ends up racing the container's binding. Excluding
// spring-boot-starter-logging makes Spring Boot skip Logback management
// entirely and defer to whatever the container already has bound.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // Provided by the external Jetty container at deploy time; only needed
    // locally for `bootRun` and tests.
    providedRuntime("org.springframework.boot:spring-boot-starter-jetty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}

tasks.test {
    useJUnitPlatform()
}
