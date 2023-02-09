package com.andretietz.gradle.update4j

import org.gradle.api.provider.Provider
import org.update4j.OS
import java.io.File
import java.io.Serializable

public data class Update4jProperty(
  val key: String,
  val value: String,
  val os: String
) : Serializable {
  init {
    //validate
    OS.fromShortName(os)
  }
}

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
  var update4jPropertiesProvider: Provider<List<Update4jProperty>>? = null,
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
            basePath: $basePath
        """.trimIndent()
  }

  companion object {
    private const val OUTPUT_DIRECTORY_DEFAULT = "update4j"
  }
}
