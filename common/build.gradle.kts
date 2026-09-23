// `common` is a plain library, not an application: no Spring Boot plugin,
// so no bootJar and no main class. Both services depend on it so they agree
// on the Kafka wire format (event envelope, payload records, canonical JSON).
plugins {
    `java-library`
}

dependencies {
    // Jackson only — deliberately no Spring here. Keeping the shared module
    // framework-free means the event contract can't drift into service logic.
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
