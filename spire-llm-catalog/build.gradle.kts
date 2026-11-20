// The public LLM model catalog: its records, the YAML parser (strict for CI, lenient for the app)
// and the validator. Framework-free library; the orchestrator loads, stores and refreshes it.
plugins {
    `java-library`
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// CI's catalog check: `--args="<head> [<base>]"`, paths relative to the repository root. The same
// parser and validator the orchestrator uses, run strictly, plus the change rules against master.
tasks.register<JavaExec>("checkCatalog") {
    group = "verification"
    description = "Validates ai-catalog/v1/catalog.yaml strictly and against a base copy."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.codespire.llmcatalog.CheckCatalogMain"
    workingDir = rootProject.projectDir
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
