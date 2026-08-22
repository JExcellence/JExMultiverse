plugins {
    `maven-publish`
    id("jexsuite.shadow-conventions")
    id("jexsuite.dependencies-yml")
}

group = "de.jexcellence.multiverse"
version = "3.7.0"
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

    // Captured at configuration time: reading project.group/version inside doLast
    // is unsupported with the configuration cache and silently prints "unspecified".
    val publishedGroup = project.group.toString()
    val publishedVersion = project.version.toString()

    dependsOn(
        // The root aggregate (artifactId "jexmultiverse") is what consumers reference
        // through libs.jexmultiverse. Omitting it here meant a version bump left the
        // catalog pointing at an artifact that had never been published, which only
        // surfaced as an unresolvable dependency in a downstream plugin.
        ":JExMultiverse:publishMavenPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-api:publishMavenPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-common:publishMavenPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-free:publishMavenShadowPublicationToMavenLocal",
        ":JExMultiverse:jexmultiverse-premium:publishMavenShadowPublicationToMavenLocal",
    )
    doLast {
        println("Published $publishedGroup:jexmultiverse*:$publishedVersion to local Maven")
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
