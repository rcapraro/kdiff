plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

val publishedModules = setOf("kdiff-annotations", "kdiff-runtime", "kdiff-processor")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "io.github.kdiff"
    version = "0.3.1"

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        if (project.name in publishedModules) {
            explicitApi()
        }
    }

    if (project.name in publishedModules) {
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set(
                            "Annotation-driven structural diff, patch and change tracking for Kotlin",
                        )
                        url.set("https://github.com/rcapraro/kdiff")
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://github.com/rcapraro/kdiff/blob/main/LICENSE")
                            }
                        }
                        developers {
                            developer {
                                id.set("rcapraro")
                                url.set("https://github.com/rcapraro")
                            }
                        }
                        scm {
                            url.set("https://github.com/rcapraro/kdiff")
                            connection.set("scm:git:https://github.com/rcapraro/kdiff.git")
                            developerConnection.set("scm:git:ssh://git@github.com/rcapraro/kdiff.git")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/rcapraro/kdiff")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                    }
                }
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
