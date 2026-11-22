pluginManagement {
    plugins { id("com.android.application") version "9.2.1" }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "IgoReviewOverlay"
include(":app")
