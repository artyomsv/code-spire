// LLM adapter: LangChain4j-backed LlmProvider — OpenAI-compatible wire
// (OpenAI, Azure, Ollama, vLLM, gateways) plus native Anthropic and Google
// Gemini clients — the review prompt builder with untrusted-content fencing,
// and the lenient findings parser.
// Framework-free library; CDI wiring happens in the host service.
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
    api(project(":spire-contract"))
    implementation(project(":spire-diff"))
    implementation("dev.langchain4j:langchain4j-open-ai:1.17.1")
    implementation("dev.langchain4j:langchain4j-anthropic:1.17.1")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.17.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
