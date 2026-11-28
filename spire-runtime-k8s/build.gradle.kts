// spire-runtime-k8s: the Kubernetes RunRuntime arm. It places a run unit as one pod under
// default-deny network policy (docs/superpowers/specs/2026-11-28-m5-kubernetes-review-runtime-design.md).
// Framework-free like spire-runtime: the fabric8 client is a plain library, not the Quarkus extension.
// Its tests run against a real cluster (kind) in the kubernetesTestModules tier.
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

// The Quarkus platform the run worker imports. It manages io.fabric8:kubernetes-client and
// io.fabric8:kubernetes-server-mock (7.8.0 in quarkus-bom 3.38.3), so this arm and the run worker
// share one client version line.
val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(project(":spire-runtime"))
    implementation(project(":spire-harness"))
    implementation(platform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.fabric8:kubernetes-client")

    // RunRuntimeContract: the rules every arm must obey. Test scope only.
    testImplementation(testFixtures(project(":spire-runtime")))
    testImplementation("io.fabric8:kubernetes-server-mock")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // Tests tagged open-cluster need a cluster that enforces no network policy. They run only in
    // openClusterTest, against a second cluster.
    useJUnitPlatform {
        excludeTags("open-cluster")
    }
    // CI keeps no test report, so a failure's message must reach the job log.
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("openClusterTest") {
    group = "verification"
    description = "Runs the tests that need a cluster whose CNI enforces no network policy."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("open-cluster")
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
