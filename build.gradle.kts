import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null && file(localPath).exists()) {
            local(localPath)
        } else {
            create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("PythonCore")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        id = "io.github.keigo.competitive-companion-runner"
        name = "Competitive Companion Runner (Unofficial)"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    processResources {
        from("LICENSE") { into("META-INF") }
    }
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }
    test {
        useJUnit()
    }
}

// Development helper: ./gradlew runIde -PrunProject=/path/to/project
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Didea.trust.all.projects=true") })
    args = listOfNotNull(providers.gradleProperty("runProject").orNull)
}
