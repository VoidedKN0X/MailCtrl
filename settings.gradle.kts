pluginManagement {
    // No gradlePluginPortal() on purpose. Every plugin this build uses is on Google's Maven or Maven
    // Central. With the portal listed, a short Maven Central outage made Gradle take a Kotlin plugin
    // marker from the portal instead; the portal serves a different file for it, so dependency
    // verification failed with "... from repository Gradle Central Plugin Repository". If a plugin
    // that only exists on the portal is ever needed, add the portal back limited to that plugin:
    // gradlePluginPortal { content { includeGroup("<plugin id>"); includeGroup("<its library's group>") } }
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MailCtrl"
include(":app")
