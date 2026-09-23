// Owns clients, the double-entry ledger and withdrawals. Has the REST API.
plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))

    // Boot 4 renamed `spring-boot-starter-web` to `-webmvc` (the servlet stack;
    // `-webflux` is the reactive one). Most tutorials still say `-web`.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Flyway owns the schema. The starter brings the engine and wires it into
    // Boot; -database-postgresql is the Postgres dialect, a separate jar since
    // Flyway 10.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // runtimeOnly: the app compiles against JDBC, not against the driver.
    runtimeOnly("org.postgresql:postgresql")

    // One test starter per feature, Boot 4 style.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")

    // Testcontainers: real Postgres and Kafka in tests, not H2 or an embedded
    // broker. Boot 4 prefixes every Testcontainers module with `testcontainers-`.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
