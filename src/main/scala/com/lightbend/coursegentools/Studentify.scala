package com.lightbend.coursegentools

/**
  * Copyright © 2016 Lightbend, Inc
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * NO COMMERCIAL SUPPORT OR ANY OTHER FORM OF SUPPORT IS OFFERED ON
  * THIS SOFTWARE BY LIGHTBEND, Inc.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

import java.io.File

object Studentify {

  def main(args: Array[String]): Unit = {

    import Helpers._
    import sbt.io.{IO => sbtio}

    implicit val exitOnFirstError: ExitOnFirstError = ExitOnFirstError(true)

    val cmdOptions = StudentifyCmdLineOptParse.parse(args)
    if (cmdOptions.isEmpty) System.exit(-1)
    val StudentifyCmdOptions(masterRepo, targetFolder, multiJVM, firstOpt, lastOpt, selectedFirstOpt, configurationFile, useConfigureForProjects, initAsGitRepo, isADottyProject, autoReloadOnBuildDefChange) = cmdOptions.get

    exitIfGitIndexOrWorkspaceIsntClean(masterRepo)
    val projectName = masterRepo.getName
    val targetCourseFolder = new File(targetFolder, projectName)

    val tmpDir = cleanMasterViaGit(masterRepo, projectName)
    val cleanMasterRepo = new File(tmpDir, projectName)

    implicit val config: MasterSettings = new MasterSettings(masterRepo, configurationFile)

    val exercises: Seq[String] = getExerciseNames(cleanMasterRepo, Some(masterRepo))

    val selectedExercises: Seq[String] = getSelectedExercises(exercises, firstOpt, lastOpt)
    println(
      s"""Processing exercises:
         |${selectedExercises.mkString("    ", "\n    ", "")}
       """.stripMargin)
    val initialExercise = getInitialExercise(selectedFirstOpt, selectedExercises)
    val sbtStudentCommandsTemplateFolder = new File("sbtStudentCommands")
    stageFirstExercise(initialExercise, new File(cleanMasterRepo, config.relativeSourceFolder), targetCourseFolder)
    copyMaster(cleanMasterRepo, targetCourseFolder)
    hideExerciseSolutions(targetCourseFolder, selectedExercises)
    createBookmarkFile(initialExercise, targetCourseFolder)
    createSbtRcFile(targetCourseFolder)
    createStudentifiedBuildFile(targetCourseFolder, multiJVM, isADottyProject, autoReloadOnBuildDefChange)
    addSbtCommands(sbtStudentCommandsTemplateFolder, targetCourseFolder)
    loadStudentSettings(masterRepo, targetCourseFolder)
    cleanUp(config.studentifyFilesToCleanUp, targetCourseFolder)
    sbtio.delete(tmpDir)
    if (initAsGitRepo) initialiseAsGit(targetCourseFolder)
  }

  def defaultGitIgnoreContent: String = {
    s"""*.class
       |*.log
       |.bookmark
       |
       |# VSCode specific
       |.vscode
       |*.code-workspace
       |
       |# Metals/Bloop specific
       |.bloop/
       |.metals/
       |.swp
       |project/metals.sbt
       |
       |# sbt specific
       |.cache/
       |.history/
       |.lib/
       |dist/*
       |target/
       |lib_managed/
       |src_managed/
       |project/boot/
       |project/plugins/project/
       |
       |# Scala-IDE specific
       |.scala_dependencies
       |.worksheet
       |.target
       |.cache
       |.classpath
       |.project
       |.settings/
       |
       |# Intellij specific
       |*.iml
       |*.idea/
       |*.idea_modules/
       |
       |# Sublime specific
       |*.sublime-project
       |*.sublime-workspace
       |
       |# OS specific
       |.DS_Store
       """.stripMargin
  }

  def initialiseAsGit(studentifiedRepo: File): Unit = {
    import ProcessDSL._

    dumpStringToFile(defaultGitIgnoreContent, new File(studentifiedRepo, ".gitignore").getPath)
    s"git init"
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(toConsoleRed(s"'git init' failed on ${studentifiedRepo.getAbsolutePath}"))
    s"git add -A"
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(toConsoleRed(s"'Failed to add initial file-set on ${studentifiedRepo.getAbsolutePath}"))
    s"""git commit -m "Initial commit""""
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(toConsoleRed(s"'Initial commit failed on ${studentifiedRepo.getAbsolutePath}"))
  }
}
