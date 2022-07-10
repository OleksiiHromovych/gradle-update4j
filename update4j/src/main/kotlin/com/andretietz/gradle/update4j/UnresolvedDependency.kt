package com.andretietz.gradle.update4j

import java.io.File

data class UnresolvedDependency(
  val group: String,
  val name: String,
  val version: String,
  val extension: String,
  val localFile: File? = null,
  val isTargetClassifier: Boolean = false,
  val classifier: String? = null
)
