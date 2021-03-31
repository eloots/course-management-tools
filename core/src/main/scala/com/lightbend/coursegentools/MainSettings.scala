package com.lightbend.coursegentools

import java.io.File

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

class MainSettings(mainRepo: File = new File("."), optConfigurationFile: Option[String]) {

  import Console._
  private val consoleColors: Set[String] =
    Set("RESET", "GREEN", "RED", "BLUE", "CYAN", "YELLOW", "WHITE", "BLACK", "MAGENTA")

  private def validateColor(settingKey: String): String = {
    val color = config.getString(settingKey)
    val colorUC = color.toUpperCase

    if (!consoleColors.contains(colorUC)) {
      println(s"Setting $settingKey: unknown color $color ")
      System.exit(-1)
    }
    colorUC
  }

  private val referenceConfig = ConfigFactory.load()

  private val cmdLineConfigFile: Option[File] =
    optConfigurationFile.map { f =>
      val configurationFile = new File(mainRepo, f)
      if (!configurationFile.exists()) {
        implicit val eofe: ExitOnFirstError = ExitOnFirstError(true)
        printError(s"No such file: ${configurationFile.getAbsolutePath}")
      }
      configurationFile
    }

  private val defaultConfigFile: Option[File] = {
    val configFile = new File(mainRepo, optConfigurationFile.getOrElse("course-management.conf"))
    if (configFile.exists()) Some(configFile) else None
  }

  private val config = (cmdLineConfigFile, defaultConfigFile) match {
    case (Some(cfg), _) =>
      ConfigFactory.parseFile(cfg).withFallback(referenceConfig)

    case (None, Some(cfg)) =>
      ConfigFactory.parseFile(cfg).withFallback(referenceConfig)

    case _ => referenceConfig
  }

  val testCodeFolders: List[String] =
    config.getStringList("studentify.test-code-folders").asScala.toList

  val commonProjectEnabled: Boolean = config.getBoolean("studentify.common-project-enabled")

  val exerciseProjectPrefix: String = config.getString("studentify.exercise-project-prefix")

  val studentifyModeSelect: String = config.getString("studentify.studentify-mode-select")

  val studentifyFilesToCleanUp: List[String] =
    config.getStringList("studentify.studentify-files-to-clean-up").asScala.toList

  val relativeSourceFolder: String = config.getString("studentify.relative-source-folder")

  val readmeInTestResources: Boolean = config.getBoolean("studentify.readme-in-test-resources")

  val useConfigureForProjects: Boolean = config.getBoolean("studentify.use-configure-for-projects")

  object studentifyModeClassic {
    private val classicModeConfig = config.getConfig("studentify.studentify-mode-classic")
    val studentifiedBaseFolder: String = classicModeConfig.getString("studentified-base-folder")
  }

  val solutionsFolder: String = config.getString("studentify.solution-folder")

  val mainBaseProjectName: String = config.getString("studentify.main-base-project-name")
  val studentifiedProjectName: String = config.getString("studentify.studentified-project-name")

  val studentTooling: StudentTooling = StudentTooling.values
    .getOrElse(config.getString("studentify.student-tooling"),
      throw new RuntimeException("Invalid value for setting studentify.student-tooling"))

  object Colors {
    val promptManColor: String = validateColor("studentify.console-colors.prompt-man-color")
    val promptCourseNameColor: String = validateColor(
      "studentify.console-colors.prompt-course-name"
    )
    val promptExerciseColor: String = validateColor(
      "studentify.console-colors.prompt-exercise-name-color"
    )
  }

  val helperScript = if (System.getProperty("run.mode") == "DEV") {
    "./bin/cmt-helpers.sh"
  } else {
    "cmt-helpers.sh"
  }

  val exercisePreamble: String = config.getString("studentify.exercise-preamble")
  val remoteRepo: String = config.getString("mainadm.remote-repo")
  val apiRoot: String = config.getString("mainadm.api-root")
}
