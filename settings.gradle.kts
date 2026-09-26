pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/jjt-ingsis/gradle-conventions")

            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }

        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "snippets-service"
