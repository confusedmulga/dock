pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Dock's own minimal ONNX Runtime build (see third_party/onnxruntime).
        maven {
            url = uri("third_party/onnxruntime/maven")
            content { includeGroup("com.dock.thirdparty") }
        }
    }
}

rootProject.name = "Dock"

include(":app")
include(":core:scanner")
include(":core:pdf")
include(":core:office")
include(":core:ocr")
