import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.claudecode"
// Version-suffix scheme: every dev build gets a unique timestamp suffix so
// "Install Plugin from Disk" in IntelliJ never short-circuits on "same
// version, treat as no-op" — that misbehaviour cost us a couple of debug
// rounds. Pass `-Prelease` for the canonical release version.
//   ./gradlew buildPlugin            → claude-code-intellij-1.0.6-dev.YYYYMMDDhhmmss.zip
//   ./gradlew buildPlugin -Prelease  → claude-code-intellij-1.0.6.zip
val baseVersion = "1.0.6"
val isRelease = project.hasProperty("release")
version = if (isRelease) {
    baseVersion
} else {
    val now = SimpleDateFormat("yyyyMMddHHmmss").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    "$baseVersion-dev.$now"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named("instrumentCode") {
    enabled = false
}

tasks.named("instrumentTestCode") {
    enabled = false
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("261.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
