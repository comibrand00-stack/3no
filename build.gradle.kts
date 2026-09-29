import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // The cloudstream gradle plugin is resolved as a raw artifact to avoid
        // JitPack's broken maven-metadata.xml unique-snapshot file serving.
        ivy("https://jitpack.io") {
            name = "jitpackArtifacts"
            patternLayout {
                artifact("com/github/recloudstream/gradle/[module]/[revision]/[module]-[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.github.recloudstream.gradle", "gradle")
            }
        }
        maven("https://jitpack.io") {
            content {
                excludeModule("com.github.recloudstream.gradle", "gradle")
            }
        }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream.gradle:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // Explicit runtime dependencies of the cloudstream gradle plugin
        // (the artifact() metadata source above resolves no transitives;
        // kotlin-stdlib comes from Gradle's embedded Kotlin).
        classpath("org.ow2.asm:asm:9.9.1")
        classpath("org.ow2.asm:asm-tree:9.9.1")
        classpath("com.github.vidstige:jadb:v1.2.1")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "comibrand00-stack/MovieProviderRepo")
    }

    android {
        namespace = "com.example.movieprovider"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val compileOnly by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        // Provided by the CloudStream app at runtime; compileOnly so it is never packaged.
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
