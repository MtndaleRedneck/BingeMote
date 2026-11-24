pluginManagement {
    // Kotlin 2.4 metadata needs R8 9.1.29+ independently of KGP/AGP compatibility.
    // Keep AGP stable while using the supported R8 override mechanism.
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.57") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BingeMote"
include(":app")
