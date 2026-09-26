// Gradle reads this file FIRST, before any build.gradle.kts.
// It defines the shape of the build: what the root project is called
// and which subprojects (modules) belong to it.

plugins {
    // Teaches Gradle where to fetch a JDK when the toolchain the build asks for
    // (25, see build.gradle.kts) is not installed. Without a toolchain
    // repository Gradle can only use a JDK it finds locally and otherwise fails
    // with "Toolchain download repositories have not been configured", so the
    // build works on a machine whose only JDK is whatever `brew install java`
    // or the CI image happened to give it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mini-custody"

include(
    // Shared code with no Spring Boot application of its own:
    // the Kafka event envelope and payload records, plus canonical JSON.
    // Both services depend on it so they agree on the wire format.
    "common",

    // Owns clients, the double-entry ledger and withdrawals. Has a REST API.
    "custody-api",

    // Owns wallet keys. Has NO signing REST endpoint on purpose:
    // it only reacts to Kafka events, so a compromised custody-api
    // cannot simply call it and ask for a signature.
    "signer",
)
