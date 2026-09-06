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

// Full stack per docs/adr/0002. Persistence (MyBatis+Oracle), Redis
// (Redisson), and Kafka are declared and coded against, but not
// live-verified in this environment (no Docker/Oracle instance available)
// — see docs/adr/0003 for what that means in practice (fakes by default).
dependencies {
    implementation("io.netty:netty-codec-http:4.1.114.Final")
    implementation("io.netty:netty-handler:4.1.114.Final")
    implementation("io.netty:netty-transport:4.1.114.Final")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("ch.qos.logback:logback-classic:1.5.8")

    implementation("org.quartz-scheduler:quartz:2.3.2")

    implementation("org.mybatis:mybatis:3.5.16")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-oracle:10.20.1")

    implementation("org.redisson:redisson:3.37.0")

    implementation("org.apache.kafka:kafka-clients:3.8.0")

    implementation("io.micrometer:micrometer-registry-prometheus:1.13.6")

    implementation("io.opentelemetry:opentelemetry-api:1.42.1")
    implementation("io.opentelemetry:opentelemetry-sdk:1.42.1")
    implementation("io.opentelemetry:opentelemetry-exporter-logging:1.42.1")

    implementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    implementation("org.glassfish:jakarta.el:4.0.2")

    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")

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
