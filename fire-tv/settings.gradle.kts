pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("cz.adaptech.tesseract4android") } } } }
rootProject.name = "SlopOffTV"
include(":app")
