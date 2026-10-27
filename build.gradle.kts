plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "io.github.mustafagulturk"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.swing")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.mustafagulturk.c2map.Main"
    applicationDefaultJvmArgs = listOf("-Dprism.vsync=false")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/** Runs a main class from the project's runtime classpath, e.g. the test sender. */
fun registerTool(name: String, main: String, desc: String) = tasks.register<JavaExec>(name) {
    group = "application"
    description = desc
    mainClass = main
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

registerTool("trackSender", "io.github.mustafagulturk.c2map.tools.TrackSender",
    "Sends synthetic TRK1 track messages over UDP (no radar needed).")
registerTool("plotSender", "io.github.mustafagulturk.c2map.tools.PlotSender",
    "Sends RDR1 plot datagrams like radar_simulator (no C++ build needed).")
registerTool("tileGenerator", "io.github.mustafagulturk.c2map.tools.DemoTileGenerator",
    "Renders offline demo tiles from bundled vector data.")
registerTool("tileDownloader", "io.github.mustafagulturk.c2map.tools.TileDownloader",
    "Downloads a small OSM tile area for offline use (respects the tile usage policy).")
registerTool("ingestBenchmark", "io.github.mustafagulturk.c2map.tools.IngestBenchmark",
    "Times decode + tracking + zone checks per datagram (no UI, no network).")
