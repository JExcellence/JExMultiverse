plugins {
    `maven-publish`
    id("jexsuite.shadow-conventions")
    id("jexsuite.dependencies-yml")
}

group = "de.jexcellence.multiverse"
version = "3.0.0"
description = "JExMultiverse - World management system"

ext["vendor"] = "JExcellence"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds both Free and Premium editions"
    dependsOn(
        ":JExMultiverse:jexmultiverse-free:shadowJar",
        ":JExMultiverse:jexmultiverse-premium:shadowJar"
    )
}

tasks.register("publishLocal") {
    group = "publishing"
    description = "Publishes all modules to local Maven repository"
    dependsOn(
        ":JExMultiverse:jexmultiverse-api:publishMavenPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-common:publishMavenPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-free:publishMavenShadowPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-premium:publishMavenShadowPublicationToMavenLocal",
    )
    doLast {
        println("Published ${project.group}:jexmultiverse-*:${project.version} to local Maven")
    }
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                artifactId = "jexmultiverse"
            }
        }
    }
}
