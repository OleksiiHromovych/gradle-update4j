package com.andretietz.gradle.update4j

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Dependency.DEFAULT_CONFIGURATION
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.update4j.Arch
import org.update4j.Configuration
import org.update4j.FileMetadata
import org.update4j.OS
import org.update4j.Property
import java.io.File
import java.net.URI
import java.net.URL


open class Update4jBundleCreator : DefaultTask() {

  @Input
  lateinit var update4jConfigurationFile: String

  @Input
  lateinit var launcherClass: String

  @Input
  lateinit var basePath: String

  @Input
  var artifactsConfiguration: String? = null

  @Input
  lateinit var remoteLocation: String

  @Input
  lateinit var targetDirectory: String

  @Input
  lateinit var resourcesDirectoryName: String

  @Input
  var update4jPropertiesProvider: Provider<List<Update4jProperty>>? = null

  @Input
  var extraFilesProvider: Provider<List<File>>? = null

  @Input
  var localLibs: Provider<Set<String>>? = null


  @Input
  lateinit var resources: List<String>

  @Input
  var useMaven: Boolean = true

  @OutputDirectory
  var outputDirectory: File = File(project.buildDir, OUTPUT_DIRECTORY_DEFAULT)

  private fun getDepsConfiguration() = artifactsConfiguration ?: DEFAULT_CONFIGURATION

  @TaskAction
  fun generateXml() {
    outputDirectory = File(project.buildDir, targetDirectory)
    logger.debug("Bundle Location: $outputDirectory")

    // TODO: Check for valid inputs!

    val builder = Configuration.builder()
      .baseUri(remoteLocation)
      .basePath(basePath)
      .launcher(launcherClass)
      .properties(
        update4jPropertiesProvider?.get()
          ?.map { (key, value, os) -> Property(key, value, OS.fromShortName(os)) }
          .orEmpty()
      )

    val onlyLocalLibs = localLibs?.get().orEmpty()

    val repos = project.repositories
      .filterIsInstance<MavenArtifactRepository>()
      // make sure to exclude maven local, since it cannot be reached
      .filter { it.name != "MavenLocal" && it.url.scheme != "file" }
      .map {
        if (!it.url.path.endsWith('/')) {
          URL(it.url.toString().plus('/'))
        }
        it.url
      }
      .toSet()

    // copy all artifacts from this project into the output dir
    project.configurations.getByName(getDepsConfiguration())
      .allArtifacts
      .map { it.file }
      .let { it + extraFilesProvider?.get().orEmpty() }
      .forEach { artifact ->
        val targetFile = File("${outputDirectory.absolutePath}/${artifact.name}")
        artifact.copyTo(targetFile, true)
        builder.file(
          FileMetadata.readFrom(artifact.absolutePath)
            .uri(artifact.name)
            .classpath(artifact.name.endsWith(".jar"))
        )
      }

    //project.configurations.getByName(DEFAULT_CONFIGURATION).resolvedConfiguration.resolvedArtifacts
    val resolvedDependencies = project.configurations.getByName(getDepsConfiguration())
      .resolvedConfiguration
      .resolvedArtifacts
      .toSet()
      .flatMap(this::createPossibleDependencies)
      .map { dependency ->
        // get download url if available
        val remoteUrl = if (useMaven && dependency.name !in onlyLocalLibs) getDownloadUrl(repos, dependency) else null
        if (remoteUrl != null) {
          return@map createExternalDependency(dependency, remoteUrl)
        } else {
          return@map LocalResolvedDependency(dependency.localFile!!)
        }
      }
    resolvedDependencies.map { resolvedDependency ->
      when (resolvedDependency) {
        is LocalResolvedDependency -> {
          resolvedDependency.file
            .copyTo(File(outputDirectory, resolvedDependency.file.name), true)
          FileMetadata
            .readFrom(resolvedDependency.file.absolutePath)
            .uri(resolvedDependency.file.name)
            .classpath(resolvedDependency.file.name.endsWith(".jar"))
            .ignoreBootConflict()
        }

        is ExternalResolvedDependency -> {
          FileMetadata
            .readFrom(resolvedDependency.file.absolutePath)
            .uri(resolvedDependency.url.toURI())
            .classpath(resolvedDependency.file.name.endsWith(".jar"))
            .os(resolvedDependency.os)
            .arch(resolvedDependency.arch)
            .ignoreBootConflict()
        }
      }
    }.forEach { builder.file(it) }

    // add resources
    resources.map { File(project.projectDir, it) }
      .forEach { file ->
        if (!file.exists()) {
          logger.warn("File \"${file.absolutePath}\" doesn't exist and will be ignored!")
        } else {
          val resourceTarget = File(outputDirectory.absolutePath, "$resourcesDirectoryName/${file.name}")
          logger.info("Copying dependency: $file to $resourceTarget")
          if (file.isFile) {
            file.copyTo(resourceTarget, true)
            builder.file(
              FileMetadata
                .readFrom(resourceTarget.absolutePath)
                .uri(resourceTarget.name)
            )
          } else if (file.isDirectory) {
            // TODO
            logger.warn("directory resources not supported atm! (tried copying: ${resourceTarget.absolutePath})")
//            file.walkTopDown().forEach { dirFile ->
//              if (dirFile.isFile) {
//                fileReferences.add(
//                  FileMetadata
//                    .readFrom(dirFile.absolutePath)
//                    .uri(dirFile.relativeTo(project.buildDir).toString())
//                )
//              }
//            }
          }
        }
      }

    // write output xml
    File("${outputDirectory.absolutePath}/$update4jConfigurationFile")
      .writeText(builder.build().toString())

    // remove downloaded OS files
    resolvedDependencies
      .filterIsInstance<ExternalResolvedDependency>()
      .filter { it.needsCleanup }
      .forEach { it.file.delete() }
  }

  private fun createExternalDependency(
    dependency: UnresolvedDependency,
    remoteUrl: URL
  ): ExternalResolvedDependency {
    var needsCleanup = false
    val file = if (dependency.localFile == null) {
      // if file is not available (happens for OS classifiers on other OSs)
      val file = File(outputDirectory, remoteUrl.path.substring(remoteUrl.path.lastIndexOf('/'), remoteUrl.path.length))
      remoteUrl.openStream().use { input -> file.outputStream().use { fout -> input.copyTo(fout) } }
      needsCleanup = true
      file
    } else {
      dependency.localFile
    }

    var os: OS? = null
    var arch: Arch? = null

    if (dependency.classifier?.contains("-") == true) {
      val (osStr, archStr) = dependency.classifier.split("-")

      if (OS.values().map { it.shortName }.contains(osStr)) {
        os = OS.fromShortName(osStr)
      }

      if (OS.values().map { it.fullName }.contains(osStr)) {
        os = osFromFullName(osStr)
      }

      if (Arch.values().map { it.id }.contains(archStr)) {
        arch = Arch.fromId(archStr)
      }
    } else {
      if (OS.values().map { it.shortName }.contains(dependency.classifier)) {
        os = OS.fromShortName(dependency.classifier)
      }
    }

    return ExternalResolvedDependency(
      file = file,
      url = remoteUrl,
      os = os,
      arch = arch,
      needsCleanup = needsCleanup
    )
  }

  private fun createPossibleDependencies(dependency: ResolvedArtifact): Collection<UnresolvedDependency> {
    val osClassified = OS.values()
      .map { it.shortName }
      .any { it == dependency.classifier }

    val target = availableTargets.find { dependency.name.endsWith(it) }

    return when {
      osClassified -> {
        OS.values().filter { it != OS.OTHER }.map {
          UnresolvedDependency(
            dependency.moduleVersion.id.group,
            dependency.moduleVersion.id.name,
            dependency.moduleVersion.id.version,
            dependency.extension ?: dependency.type,
            null,
            false,
            it.shortName
          )
        }
      }

      target != null -> {
        val baseName = dependency.moduleVersion.id.name.replace(target, "")
        availableTargets.map {
          UnresolvedDependency(
            dependency.moduleVersion.id.group,
            baseName + it,
            dependency.moduleVersion.id.version,
            dependency.extension ?: dependency.type,
            null,
            true,
            it
          )
        }
      }

      else -> {
        setOf(
          UnresolvedDependency(
            dependency.moduleVersion.id.group,
            dependency.moduleVersion.id.name,
            dependency.moduleVersion.id.version,
            dependency.extension ?: dependency.type,
            dependency.file,
            false,
            dependency.classifier
          )
        )
      }
    }
  }

  private fun getDownloadUrl(
    repos: Set<URI>,
    dependency: UnresolvedDependency
  ): URL? {
    return repos.map { repo ->
      if (dependency.isTargetClassifier) {
        URL(
          String.format(
            "%s%s/%s/%s/%s-%s%s.%s", repo.toString(),
            dependency.group.replace('.', '/'),
            dependency.name, dependency.version, dependency.name, dependency.version, "", dependency.extension
          )
        )
      } else {
        URL(
          String.format(
            "%s%s/%s/%s/%s-%s%s.%s", repo.toString(),
            dependency.group.replace('.', '/'),
            dependency.name, dependency.version, dependency.name, dependency.version,
            if (dependency.classifier != null) "-${dependency.classifier}" else "",
            dependency.extension
          )
        )
      }
    }.firstOrNull { url ->
      try {
        url.openStream().close()
        true
      } catch (error: Throwable) {
        false
      }
    }
  }

  private fun collectTransitiveDependencies(
    dependency: ResolvedDependency,
    artifacts: MutableSet<ResolvedArtifact> = mutableSetOf()
  ): Set<ResolvedArtifact> {
    dependency.moduleArtifacts.forEach { artifacts.add(it) }
    dependency.children.forEach { collectTransitiveDependencies(it, artifacts) }
    return artifacts
  }

  companion object {
    private const val OUTPUT_DIRECTORY_DEFAULT = "update4j"

    private val availableTargets = listOf(
      "windows-x64",
      "macos-x64",
      "macos-arm64",
      "linux-x64",
      "linux-arm64",
    )
  }

  private val OS.fullName: String
    get() = when (this) {
      OS.WINDOWS -> "windows"
      OS.MAC -> "macos"
      OS.LINUX -> "linux"
      OS.OTHER -> "other"
    }

  private fun osFromFullName(name: String): OS {
    return when (name) {
      "macos" -> OS.MAC
      "windows" -> OS.WINDOWS
      "linux" -> OS.LINUX
      else -> OS.OTHER
    }
  }
}
