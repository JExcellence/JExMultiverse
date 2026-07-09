plugins {
    id("jexsuite.library-conventions")
}

group = "de.jexcellence.multiverse"
version = "3.0.0"
description = "JExMultiverse Folia NMS - Runtime world loader for Folia (NMS, reflection-based, isolated)"

dependencies {
    // SPI lives in common; we implement it.
    compileOnly(project(":JExMultiverse:jexmultiverse-common"))
    compileOnly(project(":JExMultiverse:jexmultiverse-api"))

    // Paper API — for World, Environment, CraftWorld access. We do NOT
    // use paperweight-userdev: reflection against well-known NMS
    // signatures (MinecraftServer#addLevel, ServerLevel ctor) keeps the
    // build fast and the module portable across Folia patch versions
    // that don't change those symbols.
    compileOnly(libs.paper.api)
    compileOnly(libs.folialib)

    // Logging
    compileOnly(libs.slf4j.api)
    compileOnly(project(":JExPlatform"))
    compileOnly(libs.bundles.jexcellence) {
        exclude(group = "de.jexcellence.hibernate")
        isTransitive = false
    }
}
