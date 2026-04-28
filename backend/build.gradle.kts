import java.io.File
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    checkstyle
    pmd
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("net.ltgt.errorprone") version "4.1.0"
}

group = "com.docflow"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")

    errorprone("com.google.errorprone:error_prone_core:2.36.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

spotless {
    java {
        target("src/**/*.java")
        // Pin GJF: default bundled version uses javac internals removed in JDK 24+.
        googleJavaFormat("1.28.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "10.21.1"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

pmd {
    toolVersion = "7.22.0"
    isIgnoreFailures = false
    ruleSetFiles = files("config/pmd/pmd-ruleset.xml")
    ruleSets = emptyList()
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        // Excluded: GoogleJavaFormat checker — Spotless already enforces formatting.
        disable("MissingSummary")
    }
    options.compilerArgs.addAll(
        listOf(
            "-XDcompilePolicy=simple",
            "--should-stop=ifError=FLOW",
        ),
    )
}

// Error Prone on JDK 16+ requires --add-exports / --add-opens on the compiler JVM.
tasks.withType<JavaCompile>().matching { it.name == "compileTestJava" || it.name == "compileJava" }
    .configureEach {
        options.forkOptions.jvmArgs?.addAll(
            listOf(
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            ),
        )
    }

jacoco {
    toolVersion = "0.8.13"
}

val jacocoExclusions: List<String> = run {
    val f = file("config/jacoco/exclusions.txt")
    if (f.exists()) {
        f.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    } else {
        emptyList()
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(jacocoExclusions) }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(jacocoExclusions) }
            },
        ),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

abstract class GrepForbiddenStringsTask : DefaultTask() {
    @get:Internal
    abstract val forbiddenStringsFile: RegularFileProperty

    @get:Internal
    abstract val sourceRoot: DirectoryProperty

    @get:Input
    abstract val configPackagePath: Property<String>

    @TaskAction
    fun scan() {
        val file = forbiddenStringsFile.get().asFile
        if (!file.exists() || !file.isFile || !file.canRead()) {
            throw GradleException(
                "grepForbiddenStrings: required file is missing or unreadable: $file",
            )
        }

        val (literals, bareTokens) = parseSections(file.readLines(Charsets.UTF_8))
        if (literals.isEmpty() && bareTokens.isEmpty()) {
            throw GradleException(
                "grepForbiddenStrings: $file contains zero usable patterns " +
                    "(only comments/blanks). Refusing to silently pass.",
            )
        }

        val literalPatterns = literals.map { lit ->
            val quoted = "\"" + Regex.escape(lit) + "\""
            Regex(quoted)
        }
        val bareTokenPatterns = bareTokens.map { Regex(Regex.escape(it)) }

        val root = sourceRoot.get().asFile
        if (!root.exists()) {
            logger.lifecycle("grepForbiddenStrings: source root $root does not exist; nothing to scan.")
            return
        }

        val configRel = configPackagePath.get().replace('/', File.separatorChar)
        val violations = mutableListOf<String>()
        val rootPath = root.toPath()

        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .forEach { javaFile ->
                val rel = rootPath.relativize(javaFile.toPath()).toString()
                val isUnderConfig = rel.startsWith(configRel + File.separator) ||
                    rel == configRel
                if (isUnderConfig) {
                    return@forEach
                }
                javaFile.useLines { seq ->
                    seq.forEachIndexed { idx, line ->
                        for (re in literalPatterns) {
                            if (re.containsMatchIn(line)) {
                                violations.add(
                                    "${javaFile.absolutePath}:${idx + 1}: forbidden literal " +
                                        "matched by /${re.pattern}/",
                                )
                            }
                        }
                        for (re in bareTokenPatterns) {
                            if (re.containsMatchIn(line)) {
                                violations.add(
                                    "${javaFile.absolutePath}:${idx + 1}: bare-token pattern " +
                                        "/${re.pattern}/ outside com.docflow.config",
                                )
                            }
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            violations.forEach { logger.error(it) }
            throw GradleException(
                "grepForbiddenStrings found ${violations.size} violation(s); see log above.",
            )
        }
    }

    private fun parseSections(lines: List<String>): Pair<List<String>, List<String>> {
        val literals = mutableListOf<String>()
        val bareTokens = mutableListOf<String>()
        var current = literals
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                current = when (trimmed) {
                    "[bare-tokens]" -> bareTokens
                    else -> throw GradleException(
                        "grepForbiddenStrings: unknown section header $trimmed",
                    )
                }
                continue
            }
            current.add(trimmed)
        }
        return literals to bareTokens
    }
}

tasks.register<GrepForbiddenStringsTask>("grepForbiddenStrings") {
    group = "verification"
    description = "Scans backend Java sources for forbidden literals and bare-token patterns."
    forbiddenStringsFile.set(rootProject.layout.projectDirectory.file("../config/forbidden-strings.txt"))
    sourceRoot.set(layout.projectDirectory.dir("src/main/java"))
    configPackagePath.set("com/docflow/config")
}

tasks.named("check") {
    dependsOn("grepForbiddenStrings")
}
