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
// The WAR bundles its own SLF4J/Logback normally (webapp classloader keeps
// its own isolated copy — see WEB-INF/jetty-web.xml, which excludes
// org.slf4j. from Jetty's default "system classes" so the container's own
// binding never shadows it). Spring Boot's own logging management is
// disabled via -Dorg.springframework.boot.logging.LoggingSystem=none on
// the Jetty launch command (see docs/adr/0004): with it enabled, Spring's
// LogbackLoggingSystem does an instanceof check against its own
// classloader's LoggerContext type, which fails against the webapp's
// isolated copy even though it's a perfectly working Logback instance.
// Disabling Spring's management lets Logback self-initialize normally.
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

tasks.test {
    useJUnitPlatform()
}
