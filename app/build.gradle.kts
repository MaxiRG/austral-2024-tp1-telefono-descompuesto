plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // Apply the application plugin to add support for building a CLI application in Java.
    application

    id("org.springframework.boot") version "2.6.7"
    id("io.spring.dependency-management") version "1.0.11.RELEASE"
    id("org.openapi.generator") version "7.8.0"

}

group = "ar.edu.austral.inf.sd"
version = "1.0.0"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // This dependency is used by the application.
    implementation(libs.guava)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-ui:1.6.8")

    implementation("javax.validation:validation-api")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    implementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    implementation("jakarta.validation:jakarta.validation-api:3.1.0")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")

    implementation("io.swagger.core.v3:swagger-annotations:2.1.10")
    implementation("io.swagger.core.v3:swagger-models:2.1.10")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    // Define the main class for the application.
    mainClass = "ar.edu.austral.inf.sd.ApplicationKt"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

openApiGenerate {
    System.out.println(templateDir)
    templateDir.set(project.layout.projectDirectory.dir("src/main/openapi-generator").toString())
    generatorName.set("kotlin-spring")
    inputSpec.set(projectDir.resolve("src/main/openapi.json").path)
//    apiPackage.set("ar.edu.austral.inf.sd")
//    packageName.set("ar.edu.austral.inf.sd")

    // configOptions.put("appendRequestToHandler", "true")
    configOptions.put("serviceInterface", "true")

    configOptions.put("apiPackage", "ar.edu.austral.inf.sd")
    configOptions.put("basePackage", "ar.edu.austral.inf.sd")
    configOptions.put("modelPackage", "ar.edu.austral.inf.sd")
    configOptions.put("gradleBuildFile", "true")
    configOptions.put("useSpringBoot3", "true")
    configOptions.put("documentationProvider", "none")

    outputDir.set(projectDir.resolve("build/generated").absolutePath)
}

sourceSets {
    main {
        kotlin {
            srcDirs(project.layout.buildDirectory.dir("generated/src/main/kotlin"))
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}
