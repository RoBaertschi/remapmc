package robaertschi.remapmc

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.github.oshai.kotlinlogging.KotlinLogging
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.io.path.exists


data class YarnVersion(val gameVersion: String, val separator: String, val build: Int, val maven: String, val version: String, val stable: Boolean)
data class VersionManifestV2(val latest: Latest, val versions: Array<Version>) {
    enum class VersionType {
        @SerializedName(value = "release") Release,
        @SerializedName(value = "snapshot") Snapshot,
    }
    data class Latest(val release: String, val snapshot: String)
    data class Version(val id: String, val type: VersionType, val url: URL, val time: Date, val releaseTime: Date, val sha1: String, val complianceLevel: Int)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VersionManifestV2

        if (latest != other.latest) return false
        if (!versions.contentEquals(other.versions)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = latest.hashCode()
        result = 31 * result + versions.contentHashCode()
        return result
    }
}

data class PackageVersion(
    val arguments: Arguments,
    val assetIndex: AssetIndex,
    val assets: String,
    val complianceLevel: Int,
    val downloads: MCDownloads,
    val id: String,
    val javaVersion: JavaVersion,
    val libraries: Array<Library>,
    val logging: Logging,
    val mainClass: String,
    val minimumLauncherVersion: Int,
    val releaseTime: Date,
    val time: Date,
    val type: VersionManifestV2.VersionType
) {
    data class MCDownloads(val client: Download, val client_mappings: Download, val server: Download, val server_mappings: Download)
    data class Download(val sha1: String, val size: Int, val url: URL)
    // TODO: Add proper types, instead of any use String and a rule
    data class Arguments(val game: Array<Any>, val jvm: Array<Any>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Arguments

            if (!game.contentEquals(other.game)) return false
            if (!jvm.contentEquals(other.jvm)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = game.contentHashCode()
            result = 31 * result + jvm.contentHashCode()
            return result
        }
    }
    data class AssetIndex(val id: String, val sha1: String, val size: Int, val totalSize: Int, val url: URL)
    data class JavaVersion(val component: String, val majorVersion: Int)
    data class Library(val downloads: Downloads, val rules: Any)
    data class Downloads(val artifact: Artifact, val name: String)
    data class Artifact(val path: String, val sha1: String, val size: Int)
    data class Logging(val client: ClientLogging)
    data class ClientLogging(val argument: String, val file: LoggingFile, val type: String)
    data class LoggingFile(val id: String, val sha1: String, val size: Int)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PackageVersion

        if (arguments != other.arguments) return false
        if (assetIndex != other.assetIndex) return false
        if (assets != other.assets) return false
        if (complianceLevel != other.complianceLevel) return false
        if (downloads != other.downloads) return false
        if (id != other.id) return false
        if (javaVersion != other.javaVersion) return false
        if (!libraries.contentEquals(other.libraries)) return false
        if (logging != other.logging) return false
        if (mainClass != other.mainClass) return false
        if (minimumLauncherVersion != other.minimumLauncherVersion) return false
        if (releaseTime != other.releaseTime) return false
        if (time != other.time) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = arguments.hashCode()
        result = 31 * result + assetIndex.hashCode()
        result = 31 * result + assets.hashCode()
        result = 31 * result + complianceLevel
        result = 31 * result + downloads.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + javaVersion.hashCode()
        result = 31 * result + libraries.contentHashCode()
        result = 31 * result + logging.hashCode()
        result = 31 * result + mainClass.hashCode()
        result = 31 * result + minimumLauncherVersion
        result = 31 * result + releaseTime.hashCode()
        result = 31 * result + time.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

}

val logger = KotlinLogging.logger {  }
val downloadLogger = KotlinLogging.logger("download")

fun download(urlString: String, outputFile: Path) {
    val uri = URI.create(urlString)
    download(uri.toURL(), outputFile)
}
fun download(url: URL, outputFile: Path) {
    downloadLogger.info { "Starting download for $url to $outputFile" }
    val rbc = Channels.newChannel(url.openStream())
    val fos = FileOutputStream(outputFile.toFile())
    fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
    downloadLogger.info { "Download successfully finished" }
}

fun remap(mapping: Path, input: Path, output: Path, from: String, to: String, classMappings: Map<String, String>) {
    val builder = TinyRemapper.newRemapper()
        .withMappings ( TinyUtils.createTinyMappingProvider(mapping, from, to) )
        .renameInvalidLocals(true)
        .rebuildSourceFilenames(true)
        .invalidLvNamePattern(Pattern.compile("\\$\\$\\d+"))
        .inferNameFromSameLvIndex(true)
        // TODO: Mixin Support
        //.extension(MixinExtension())
    builder.withMappings { out -> classMappings.forEach(out::acceptClass) }
    val remapper = builder.build()

    OutputConsumerPath.Builder(output).assumeArchive(true).build().use {
        consumer ->
        consumer.addNonClassFiles(input)
        remapper.readInputsAsync(input)
        remapper.readClassPath(input)
        remapper.apply(consumer)
        remapper.finish()
    }
}

fun run(mcVersion: String) {

    logger.info { "Starting to download yarn mappings for minecraft version $mcVersion" }

    val gson = Gson()
    val dir = Paths.get("remapmc")
    if (!dir.exists()) {
        Files.createDirectory(dir)
    }

    val versionManifestV2 = gson.fromJson(URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").toURL().openStream().reader(), VersionManifestV2::class.java)
    val version = versionManifestV2.versions.find { it.id == mcVersion }
    if (version == null) {
        logger.error { "Could not find minecraft with the version $mcVersion" }
        throw RuntimeException("Could not find minecraft with the version $mcVersion")
    }

    val packageVersion = gson.fromJson(version.url.openStream().reader(), PackageVersion::class.java)

    val minecraftJar = dir.resolve("minecraft-client-$mcVersion.jar")
    if (!minecraftJar.exists()) {
        download(packageVersion.downloads.client.url, minecraftJar)
    }

    val intermediaryPath = dir.resolve("minecraft-intermediary-$mcVersion.tiny")
    if (!intermediaryPath.exists()) {
        download(
            "https://github.com/FabricMC/intermediary/raw/master/mappings/$mcVersion.tiny",
            intermediaryPath
        )
    }

    val yarnVersion = gson.fromJson(URI.create("https://meta.fabricmc.net/v2/versions/yarn").toURL().openStream().reader(), Array<YarnVersion>::class.java)
    val filteredVersions = yarnVersion.filter { it.stable }.filter { it.gameVersion == mcVersion }

    var highestBuild = 0
    for (filteredYarnVersion in filteredVersions) {
        if (highestBuild < filteredYarnVersion.build) {
            highestBuild = filteredYarnVersion.build
        }
    }
    val latestStable = filteredVersions.find { it.build == highestBuild }
    if (latestStable == null) {
        logger.error { "Could not find the latest stable version of the yarn mappings" }
        return
    }

    val yarnPath = dir.resolve("yarn-${latestStable.version}-mergedv2.jar")
    if (!yarnPath.exists()) {
        download(
            "https://maven.fabricmc.net/net/fabricmc/yarn/${latestStable.version}/yarn-${latestStable.version}-mergedv2.jar",
            yarnPath
        )
    }

    val unzipDir = dir.resolve("yarn-${latestStable.version}")
    if (!unzipDir.exists()) {
        try {

            Files.createDirectory(unzipDir)
            // Unzip yarn
            ZipFile(yarnPath.toFile()).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val resolvedPath = unzipDir.resolve(entry.name).normalize()
                    if (!resolvedPath.startsWith(unzipDir)) {
                        // see: https://snyk.io/research/zip-slip-vulnerability
                        throw RuntimeException("Entry with an illegal path: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(resolvedPath)
                    } else {
                        Files.createDirectories(resolvedPath.parent)

                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, resolvedPath)
                        }
                    }

                }
            }
        } catch (e: Exception) {
            if (!unzipDir.toFile().deleteRecursively()) {
                throw RuntimeException("Error while extracting yarn jar, a second error occurred while deleting the resulting directory, aborting.\n!!!! Please try to delete $unzipDir yourself.\n Original Error: $e")
            }
            throw e
        }
    }

    // Remap
    val intermediaryJar = dir.resolve("minecraft-client-intermediary-$mcVersion.jar")
    remap(intermediaryPath, minecraftJar, intermediaryJar, "official", "intermediary", mapOf())

    logger.info { latestStable }
}

fun main(args: Array<String>) {
    lateinit var mcversion: String
    var ok = true

    if (args.size >= 2) {
        if (args[0] == "--mcversion") {
            mcversion = args[1]
        } else ok = false;
    }  else ok = false;
    if(!ok) {
        println("remapmc --mcversion <version>")
        return
    }

    try {
        run(mcversion)
        logger.info { "Successfully remapped and decompiled MC. Look in the new 'remapmc' folder" }
    } catch (e: Exception) {
        logger.error { "Error: The follow exception occurred: ${e.message}"}
        if (e.cause != null)
            logger.error { "With the following cause: ${e.cause}" }
        logger.error { "Stacktrace: ${e.stackTraceToString()}" }
    }
}