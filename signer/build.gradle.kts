// Owns wallet keys and is the only thing that can sign.
//
// Note what is NOT here: spring-boot-starter-web. The signer has no HTTP
// server for signing on purpose — it only reacts to Kafka events, so a
// compromised custody-api cannot simply call it and ask for a signature.
// (Actuator's endpoints therefore have no server to be exposed on, which for
// this service is the correct amount of attack surface.)
plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ---- Signing ----------------------------------------------------------
    //
    // `org.web3j:crypto`, and deliberately not `org.web3j:core`. Core is the
    // batteries-included client: it would bring okhttp, RxJava 2, a WebSocket
    // implementation, a Unix-socket JNR binding and the AWS KMS SDK, none of
    // which this service uses. On a component whose entire design argument is
    // that it has the smallest possible surface, and whose dependencies are a
    // merge gate (Trivy, on every pull request), that is a lot of code to carry
    // for three JSON-RPC calls. Those three are made with Spring's RestClient
    // instead — see EthereumRpc, which is shorter than this comment's worth of
    // dependency tree.
    //
    // What crypto is used for is the part nobody should hand-roll: secp256k1
    // ECDSA with RFC 6979 deterministic `k`, and the RLP encoding of an
    // EIP-1559 transaction. A bug in either is a lost key or a lost payment.
    implementation("org.web3j:crypto:6.0.0")

    // web3j 6.0.0 resolves BouncyCastle 1.80, which has three advisories with
    // released fixes — two CRITICAL — so the dependency gate rejects it, and
    // rightly: this is the library doing the secp256k1 arithmetic. None of the
    // three is reachable from this code (GOST-CTR, X.509 name constraints, and
    // ASN.1 parsing; the signer does none of those), but "we do not call that
    // method" is a claim that stops being true the first time somebody adds a
    // certificate check, and the fix is a version number.
    //
    // A constraint rather than an explicit dependency: this module does not use
    // BouncyCastle directly, so declaring it would assert a relationship that
    // does not exist and would survive web3j dropping it. Delete this once web3j
    // ships a release that resolves 1.85 or newer on its own — Trivy will not let
    // it rot silently either way.
    constraints {
        implementation("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("CVE-2025-14813 and CVE-2026-8763 (CRITICAL) and CVE-2026-13506 (HIGH) in 1.80")
        }
    }

    // RestClient, for the JSON-RPC calls above. The library, not the starter:
    // spring-web has no servlet container in it, so this stays a service that
    // makes HTTP calls and does not answer them.
    implementation("org.springframework:spring-web")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")

    // Polling with a deadline, for the assertions about what another thread
    // will shortly have done: the relay publishing, Anvil mining a block.
    testImplementation("org.awaitility:awaitility")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
