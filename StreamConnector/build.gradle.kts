plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group =
    "com.ksiu"
version =
    "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(
        platform(
            "org.junit:junit-bom:6.0.0"
        )
    )
    testImplementation(
        "org.junit.jupiter:junit-jupiter"
    )
    testRuntimeOnly(
        "org.junit.platform:junit-platform-launcher"
    )

    implementation("io.socket:socket.io-client:1.0.2")
    implementation("org.json:json:20231013")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}

val mainPackage = "com.ksiu.commons"
tasks {
    test {
        useJUnitPlatform()
    }
    shadowJar {
        relocate("io.socket", "$mainPackage.shadow.io.socket")
        relocate("org.json", "$mainPackage.shadow.org.json")
        relocate("okhttp3", "$mainPackage.shadow.okhttp3")
        relocate("okio", "$mainPackage.shadow.okio")
        relocate("org.java_websocket", "$mainPackage.shadow.org.java_websocket")
    }
    jar {
        enabled = false
    }
}
