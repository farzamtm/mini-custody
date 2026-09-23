// Gradle reads this file FIRST, before any build.gradle.kts.
// It defines the shape of the build: what the root project is called
// and which subprojects (modules) belong to it.
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
