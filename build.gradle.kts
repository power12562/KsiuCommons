subprojects {
    apply(plugin = "java")
    tasks.withType<Jar> {
        destinationDirectory.set(file("${rootProject.projectDir}/lib"))
    }
}