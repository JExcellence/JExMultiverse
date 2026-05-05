plugins {
    id("jexsuite.library-conventions")
}

group = "de.jexcellence.multiverse"
version = "3.0.0"
description = "JExMultiverse API - Public API for third-party plugin integration"

dependencies {
    compileOnly(libs.paper.api)
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                groupId = "de.jexcellence.multiverse"
                artifactId = "jexmultiverse-api"
                version = project.version.toString()
                pom {
                    name.set("JExMultiverse API")
                    description.set("Public API for JExMultiverse world management system")
                }
            }
        }
    }
}
