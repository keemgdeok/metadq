plugins {
    application
    java
    id("com.diffplug.spotless") version "8.10.2"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.keemgdeok"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("org.apache.iceberg:iceberg-core:1.11.0")
    runtimeOnly("org.apache.iceberg:iceberg-aws:1.11.0")
    runtimeOnly("org.apache.iceberg:iceberg-aws-bundle:1.11.0")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.3")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

application {
    mainClass = "io.github.keemgdeok.metadq.cli.Metadq"
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.md", "docs/**/*.md", "examples/**/*.yml", ".github/**/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Jar>().configureEach {
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-metadq" }
    }
    from("NOTICE") {
        into("META-INF")
        rename { "NOTICE-metadq" }
    }
}

tasks.test {
    dependsOn(tasks.shadowJar)
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName = "metadq.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

val benchmark = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("metadataBenchmark") {
    group = "verification"
    description = "Measure metadata collection for synthetic Iceberg file counts."
    classpath = benchmark.runtimeClasspath
    mainClass = "io.github.keemgdeok.metadq.benchmark.MetadataBenchmark"
    maxHeapSize = "2g"
    if (project.hasProperty("counts")) {
        args(project.property("counts").toString().split(","))
    }
}
