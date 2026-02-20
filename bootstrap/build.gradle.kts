plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":core"))
    implementation("org.spongepowered:mixin:0.8.5")
    implementation("io.github.llamalad7:mixinextras-common:0.3.5")
    implementation("com.github.bawnorton.mixinsquared:mixinsquared-common:0.2.0")
    compileOnly(files("libs/HytaleServer.jar"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("slim")
}
