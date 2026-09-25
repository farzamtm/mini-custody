import java.math.BigDecimal

// Root build file. It builds nothing itself; it holds the configuration
// that every module shares, so the module files stay short.
//
// `apply false` means: make the plugin available to subprojects, but don't
// apply it here. The root project is not a Spring Boot app.
plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false

    // Quality and security tooling. Applied to every module in `subprojects`
    // below, so `./gradlew check` runs the same gates CI runs.
    id("com.diffplug.spotless") version "8.10.2" apply false
    id("com.github.spotbugs") version "6.5.11" apply false

    // Produces a CycloneDX SBOM of the resolved runtime classpath. CI feeds it
    // to Trivy: Gradle has no lockfile a scanner could read on its own.
    id("org.cyclonedx.bom") version "3.4.1"

    // Turns custody-api/src/main/resources/openapi.yaml into the interfaces the
    // controllers implement. Applied in the module that has a contract.
    id("org.openapi.generator") version "7.14.0" apply false
}

// Bootstrap classes are not worth testing, and leaving them in the denominator
// makes the coverage number mean less, not more.
val coverageExclusions = listOf(
    "**/*Application.class",
)

// The floor, not the target. It ratchets up as milestones land; it exists so a
// pull request cannot quietly delete tests. Override with `-PcoverageMinimum=0`.
// M0: 0.50 (there was nothing to cover). M1: 0.85, with the ledger at 0.94.
// M2: 0.90, with custody-api at 0.96.
// M4: 0.92, with custody-api at 0.96 and common at 0.97.
// M6: 0.93, with custody-api at 0.96, common at 0.97 and the signer at 0.94.
// M3: 0.93, with custody-api at 0.97, common at 0.96 and the signer at 0.94.
// M7: 0.94, with custody-api at 0.9718, common at 0.9500 and the signer at 0.9430.
//
// The rule is applied per module, so the floor is set by whichever is lowest — still
// the signer. M6 left it at 0.9412 and declined to move the floor to 0.94, because half
// a line of margin is a floor that fails on the next unrelated change. M7 added the
// results-signing key to that module and covered its branches, which puts it at 0.9430:
// about a line and a half of margin. That is thin but no longer token, and the ratchet
// exists to be moved when a milestone earns it. Whoever trips it next should write the
// test rather than reach for -PcoverageMinimum=0.
val coverageMinimum = BigDecimal((findProperty("coverageMinimum") ?: "0.94").toString())

// Applied to the root project AND every module.
allprojects {
    group = "com.farzam"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")
    apply(plugin = "checkstyle")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.github.spotbugs")

    java {
        // A toolchain pins the JDK used to compile and test, independently of
        // whatever `java` happens to be on your PATH (yours is 27).
        // Gradle finds the Homebrew JDK 25 automatically, or downloads one.
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // The Spring Boot BOM: it pins versions for every Spring dependency, which
    // is why the module files below list dependencies without version numbers.
    // Boot 4.1.1 pins Tomcat 11.0.24, which has three CRITICAL
    // authentication-bypass advisories (CVE-2026-65182, -65905, -68525) and no
    // Boot release carrying the fix yet. Overriding the BOM's version property
    // is the supported way to move a single managed dependency forward.
    // Delete this once the Boot version above ships 11.0.25 or newer — the
    // Trivy gate in CI will not let it rot silently either way.
    extra["tomcat.version"] = "11.0.26"

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        // Boot 4 split the old all-in-one `spring-boot-starter-test` into one
        // test starter per feature, so each module declares its own below.
        // Only JUnit's launcher is universal — Gradle needs it to run tests.
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        // The compiler is the cheapest static analyser available, and it is off
        // by default. `-Werror` makes a new warning fail the build on the pull
        // request that introduced it, rather than a year later.
        // `-processing` is excluded because Spring's annotation processors are
        // not always present, and `-Werror` would fail on their absence.
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers is chatty on failure; showing output saves a trip to the report.
        testLogging {
            events("passed", "skipped", "failed")
        }
        // JaCoCo writes exec data during the test run, so the report task has
        // to wait for the tests. Wiring it here means `./gradlew test` always
        // leaves a usable report behind.
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    // ---- Formatting -------------------------------------------------------
    //
    // Spotless is the only formatting authority: no style discussion survives
    // `./gradlew spotlessApply`. See config/spotless/eclipse-format.properties
    // for what it does and does not touch, and why it is Eclipse JDT rather
    // than google-java-format.
    the<com.diffplug.gradle.spotless.SpotlessExtension>().apply {
        java {
            target("src/**/*.java")
            eclipse().configFile(rootProject.file("config/spotless/eclipse-format.properties"))
            // The default engine for this step is google-java-format, which does
            // not run on JDK 25; the JavaParser-based one has no such problem.
            removeUnusedImports("cleanthat-javaparser-unnecessaryimport")
            // Static imports first, then everything else alphabetically in one
            // block. Grouping by top-level package buys nothing and costs a
            // merge conflict every time two branches add an import.
            importOrder("\\#", "")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    // ---- Style and bug patterns -------------------------------------------
    the<CheckstyleExtension>().apply {
        toolVersion = "14.1.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        // Checkstyle's own default is to warn and carry on, which trains people
        // to ignore it. A violation is a build failure.
        maxWarnings = 0
        isIgnoreFailures = false
    }

    the<com.github.spotbugs.snom.SpotBugsExtension>().apply {
        toolVersion = "4.10.4"
        // MAX effort is slower but finds inter-procedural bugs; this codebase is
        // small enough that the extra minute does not matter.
        effort = com.github.spotbugs.snom.Effort.MAX
        reportLevel = com.github.spotbugs.snom.Confidence.LOW
        excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
    }

    dependencies {
        // find-sec-bugs turns SpotBugs into a security scanner: SQL injection,
        // weak crypto, predictable RNG, path traversal, hardcoded keys. For a
        // private repository this is the SAST layer, since GitHub's CodeQL
        // needs Advanced Security.
        "spotbugsPlugins"("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")

        // `@SuppressFBWarnings(value = ..., justification = ...)`. The exclude
        // filter is for patterns that are wrong across the whole codebase; this
        // is for the single line where the tool is wrong and the reason has to
        // sit next to the code, where review will see it. compileOnly: it is an
        // annotation the analyser reads, not something that ships. It is needed
        // on the test classpath too: the annotation has CLASS retention, so
        // javac reads it back out of the main class files when compiling tests
        // and warns — fatally, under -Werror — if it cannot resolve it.
        "compileOnly"("com.github.spotbugs:spotbugs-annotations:4.10.4")
        "testCompileOnly"("com.github.spotbugs:spotbugs-annotations:4.10.4")
    }

    tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
        reports.create("html") { required = true }
        reports.create("sarif") { required = true }
    }

    // Test sources get Spotless and Checkstyle but not SpotBugs: the bug
    // patterns it looks for (ignored return values, unclosed resources) are
    // routine and deliberate in tests, and the noise would bury real findings.
    tasks.named("spotbugsTest") { enabled = false }

    // ---- Coverage ---------------------------------------------------------
    the<JacocoPluginExtension>().apply { toolVersion = "0.8.15" }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true // consumed by the CI coverage comment
            html.required = true
        }
        classDirectories.setFrom(files(classDirectories.files.map {
            fileTree(it) { exclude(coverageExclusions) }
        }))
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        classDirectories.setFrom(files(classDirectories.files.map {
            fileTree(it) { exclude(coverageExclusions) }
        }))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = coverageMinimum
                }
            }
        }
    }

    tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
}

// A single SBOM for the whole build, so the CI scanner has one file to read.
tasks.withType<org.cyclonedx.gradle.BaseCyclonedxTask> {
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    // A serial number changes on every run, which makes every SBOM commit or
    // artifact diff look like a real change. Off, so the file is reproducible.
    includeBomSerialNumber = false
}
