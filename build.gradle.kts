// Root build file. It builds nothing itself; it holds the configuration
// that every module shares, so the module files stay short.
//
// `apply false` means: make the plugin available to subprojects, but don't
// apply it here. The root project is not a Spring Boot app.
plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

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

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers is chatty on failure; showing output saves a trip to the report.
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
