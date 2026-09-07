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
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // spring-boot-starter-jetty (below) is providedRuntime, and Spring
    // Boot's bootWar packages any dependency reachable through a
    // providedRuntime path into WEB-INF/lib-provided/ instead of
    // WEB-INF/lib/ — even when the same dependency is ALSO declared as
    // implementation elsewhere, providedRuntime membership wins. slf4j-api
    // came in transitively through spring-boot-starter-jetty this way and
    // was simply missing from the deployed webapp's actual runtime
    // classpath (ClassNotFoundException: org.slf4j.Logger/LoggerFactory).
    // Excluding it from the providedRuntime dependency, while keeping our
    // own explicit `implementation` declaration, is what actually forces
    // it into WEB-INF/lib/.
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Provided by the external Jetty container at deploy time; only needed
    // locally for `bootRun` and tests.
    providedRuntime("org.springframework.boot:spring-boot-starter-jetty") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
}
