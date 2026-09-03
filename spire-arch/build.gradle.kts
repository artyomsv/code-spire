// Architecture invariants for the whole repo, enforced as tests so a violation
// fails the build instead of relying on review vigilance.
//
// Deliberately depends on NO other module: the checks read source files as text,
// which sees string literals ("gitlab") and comments — things a bytecode-level
// check (ArchUnit) cannot see. The provider-name leaks that actually caused
// cross-provider bugs were string comparisons, not type references.
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    // Hand the repo root in explicitly rather than guessing from the working
    // directory, so the scan works the same from an IDE and from Gradle.
    systemProperty("spire.repoRoot", rootProject.projectDir.absolutePath)

    // The scanned sources are real inputs to this task. Without declaring them,
    // Gradle's up-to-date check only considers this module's own classpath — so
    // editing another module's source and re-running would report a cached PASS.
    // A silent false green is worse than no check at all.
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("spire-*/src/main/java/**/*.java")
            // DockerTestsAreSerialisedTest scans TEST sources, because a module starts driving the
            // daemon by an edit to src/test. Undeclared, that edit changes no input of this task, so
            // Gradle reports UP-TO-DATE or FROM-CACHE — a cached PASS from the very edit the check
            // exists to catch. Measured, not assumed: with this line absent, adding a Docker-driving
            // test to another module left `:spire-arch:test` UP-TO-DATE and the guard silent.
            include("spire-*/src/test/java/**/*.java")
        }
    ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)

    // TestTierCoverageTest reads these two files as text. Undeclared, they are invisible to the
    // up-to-date check, so adding a module to settings.gradle.kts without assigning it a CI tier
    // would report a cached PASS — from the very edit the check exists to catch.
    // TestTierCoverageTest reads the first two as text, and ImageBuildSeesEveryModuleTest reads
    // settings against the Dockerfile. Undeclared, they are invisible to the up-to-date check, so
    // adding a module without assigning it a CI tier — or without copying it into the image's
    // dependency layer — would report a cached PASS from the very edit the checks exist to catch.
    inputs.files(
        rootProject.file("settings.gradle.kts"),
        rootProject.file("build.gradle.kts"),
        rootProject.file("Dockerfile"),
        rootProject.file("LICENSING.md"),
        // DockerTestsAreSerialisedTest asserts org.gradle.parallel is still on: the Docker lock is
        // only worth its cost while the build is parallel. Turning it off is a one-line edit here.
        rootProject.file("gradle.properties")
    ).withPropertyName("buildDeclarations").withPathSensitivity(PathSensitivity.RELATIVE)

    // NoCorporateEnvironmentIsBakedIntoAnImageTest reads the two RUN-UNIT Dockerfiles as text.
    // Undeclared they are invisible to the up-to-date check, so adding an ENV HTTPS_PROXY to the
    // agent image would report a cached PASS from the very edit the check exists to catch --
    // the same hole the scanned-sources block above was added to close.
    inputs.files(
        rootProject.file("deploy/agent/codex/Dockerfile"),
        rootProject.file("spire-publisher/Dockerfile")
    ).withPropertyName("runUnitImages").withPathSensitivity(PathSensitivity.RELATIVE)

    // ModuleLicensingIsDeclaredTest reads every module LICENSE. Undeclared they are invisible to
    // the up-to-date check, and the check then reports a CACHED PASS over a licence file that was
    // just made wrong — measured, not assumed: with this block absent, restoring the exact
    // copy-paste bug the test was written for produced BUILD SUCCESSFUL.
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("spire-*/LICENSE")
        }
    ).withPropertyName("moduleLicences").withPathSensitivity(PathSensitivity.RELATIVE)
}
