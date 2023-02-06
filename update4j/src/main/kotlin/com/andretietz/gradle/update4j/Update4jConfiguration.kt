package com.andretietz.gradle.update4j

import org.gradle.api.internal.provider.DefaultListProperty
import org.gradle.api.provider.Provider
import org.update4j.OS
import java.io.File

open class Update4jConfiguration @JvmOverloads constructor(
  var resourcesFolderName: String = ".",
  var resources: List<String> = listOf(),
  var libraryFolderName: String? = null,
  var configurationFileName: String = "update.xml",
  var useMaven: Boolean = true,
  var signingKey: String? = null,
  var launcherClass: String? = null,
  var remoteLocation: String? = null,
  var artifactsConfiguration: String? = null,
  var update4jProperties: List<Triple<String, String, OS>> = emptyList(),
  var extraFilesProvider: Provider<List<File>>? = null,
  var basePath: String? = null,
  var bundleLocation: String = OUTPUT_DIRECTORY_DEFAULT
) {
  override fun toString(): String {
    return """
            libraryFolderName: $libraryFolderName,
            configurationFileName: $configurationFileName,
            launcherClass: $launcherClass,
            remoteLocation: $remoteLocation,
            bundleLocation: $bundleLocation,
            resourcesFolderName: $resourcesFolderName,
            artifactsConfiguration: $artifactsConfiguration,
            resources: $resources
            properties: $update4jProperties
            basePath: $basePath
        """.trimIndent()
  }

  companion object {
    private const val OUTPUT_DIRECTORY_DEFAULT = "update4j"
  }
}
