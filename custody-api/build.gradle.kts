// Owns clients, the double-entry ledger and withdrawals. Has the REST API.
plugins {
    java
    id("org.springframework.boot")
    id("org.openapi.generator")
}

// ---- API-first ------------------------------------------------------------
//
// src/main/resources/openapi.yaml is the contract, and it is written by hand
// before the code. This task turns it into one interface per tag, which the
// controllers implement. The value is not saved typing — it is that the
// contract and the implementation cannot drift: change a response type in the
// YAML and the controller stops compiling.
//
// `interfaceOnly`: generate the interface and the DTOs, never a controller
// class. A generated controller would have to be regenerated (and its body
// lost) on every contract change.
openApiGenerate {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("src/main/resources/openapi.yaml").asFile.path
    outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.path
    apiPackage = "com.farzam.custody.api"
    modelPackage = "com.farzam.custody.api.model"
    // `Withdrawal`, `Account` and `WithdrawalStatus` are all names the domain
    // already uses, and the two meanings are genuinely different: the entity is
    // what the database holds, the DTO is what the contract promises. Suffixing
    // the generated side keeps both importable in the same file and makes every
    // mapper read as an explicit crossing of that boundary.
    modelNameSuffix = "Dto"
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useJakartaEe" to "true",
        // One interface per tag (WithdrawalsApi, AccountsApi, …) rather than one
        // per path, so a controller implements a coherent slice of the API.
        "useTags" to "true",
        // No `default` method bodies returning 501: an unimplemented operation
        // should be a compile error in the controller, not a runtime surprise.
        "skipDefaultInterface" to "true",
        // Bean Validation annotations (@Pattern, @NotNull, @Size) generated from
        // the schema, so the contract's constraints are enforced at the edge
        // rather than restated by hand in Java and allowed to disagree.
        "useBeanValidation" to "true",
        "performBeanValidation" to "true",
        // Plain java.util.Optional and no JsonNullable wrapper: this API has no
        // PATCH, so "absent" and "null" never need telling apart.
        "openApiNullable" to "false",
        // Neither swagger-core annotations nor a /swagger-ui endpoint. The YAML
        // is the documentation, and it is already the input.
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        // A timestamp in generated code makes every build produce a different
        // file, which defeats Gradle's up-to-date checks.
        "hideGenerationTimestamp" to "true",
    )
    // Interfaces and DTOs only. Left to itself the generator also writes a
    // pom.xml, a README, a Spring `@Configuration` in an `org.openapitools`
    // package that this application does not component-scan, and test stubs —
    // none of which belong in a build that already has all of those.
    globalProperties = mapOf("apis" to "", "models" to "")
}

// ---- Generated code is compiled on the generator's terms, not ours ---------
//
// The root build compiles with `-Xlint:all -Werror`, and this generator's output
// does not survive that: 7.14 emits `org.springframework.lang.Nullable`, which
// Spring Framework 7 deprecated in favour of JSpecify, and it puts its "do not
// edit" banner above the `package` line, which javac reads as a dangling doc
// comment. Neither is fixable from this repository.
//
// Relaxing the flags for the whole module would be the easy fix and the wrong
// one: the warnings are worth most exactly where a human is typing. So the
// generated tree gets a source set of its own, compiled quietly, and `main`
// depends on its output. Checkstyle, SpotBugs and JaCoCo are all per-source-set,
// so they ignore it for free rather than by a list of path exclusions that would
// need maintaining.
val generated: SourceSet = sourceSets.create("generated") {
    java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
}

tasks.named<JavaCompile>("compileGeneratedJava") {
    dependsOn(tasks.named("openApiGenerate"))
    options.compilerArgs.clear()
    // -parameters survives the reset on purpose. Without it the interface's parameter names are
    // gone by runtime, and a Bean Validation failure on the Idempotency-Key header reports itself
    // against "arg0" instead of naming the header. Spring Boot puts it on `compileJava` for exactly
    // this reason; clearing the args took it off this task with everything else.
    options.compilerArgs.addAll(listOf("-nowarn", "-parameters"))
}

// The style gates have nothing to say to a machine.
tasks.named("checkstyleGenerated") { enabled = false }
tasks.named("spotbugsGenerated") { enabled = false }

// `./gradlew :custody-api:bootRun` is a developer asking for a local instance,
// and a local instance with no way to put money in it is not much use. The
// profile is what maps POST /dev/deposits; a real deployment sets its own and
// the endpoint's bean is never created. Override with
// `-Dspring.profiles.active=...` if you want the production shape locally.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=dev")
}

dependencies {
    // What the generated interfaces and DTOs are written against. Deliberately
    // spelled out rather than inherited from `implementation`, which contains the
    // generated output itself and would make the compile task depend on itself.
    "generatedImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "generatedImplementation"("org.springframework.boot:spring-boot-starter-validation")

    // A directory of classes as a dependency: it lands on the compile classpath,
    // the test classpath and the runtime classpath, and `bootJar` packages a
    // classpath directory into BOOT-INF/classes. Gradle infers from the
    // SourceSetOutput that `compileJava` has to wait for `compileGeneratedJava`.
    implementation(generated.output)

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
