plugins {
    java
    application
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

// Redis (Redisson) and Kafka are declared and coded against, but not
// live-verified in this environment — see docs/adr/0003 for what that means
// in practice (fakes by default).
dependencies {
    // Resolved from the composite build in settings.gradle.kts, not from a
    // repository — the version here is only what Gradle needs to match on.
    // Netty, Jackson, MyBatis and Bean Validation arrive through it as `api`
    // dependencies; Hikari, the Postgres driver and Flyway arrive at runtime.
    implementation("com.sunmoon:sun-moon-platform-core:0.1.0")

    implementation("org.quartz-scheduler:quartz:2.3.2")
    implementation("org.redisson:redisson:3.37.0")
    implementation("org.apache.kafka:kafka-clients:3.8.0")

    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.8")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.13.0")
}

application {
    mainClass.set("com.sunmoon.platform.Bootstrap")
}

tasks.test {
    useJUnitPlatform()
}
