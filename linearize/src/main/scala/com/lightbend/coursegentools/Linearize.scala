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

object Linearize {

  def main(args: Array[String]): Unit = {

    import java.io.File

    import Helpers._
    import sbt.io.{IO => sbtio}

    implicit val eofe: ExitOnFirstError = ExitOnFirstError(true)

    val cmdOptions = LinearizeCmdLineOptParse.parse(args)
    if (cmdOptions.isEmpty) System.exit(-1)
    val LinearizeCmdOptions(mainRepoPath,
                            linearizedOutputFolder,
                            multiJVM,
                            forceDeleteExistingDestinationFolder,
                            configurationFile,
                            isADottyProject,
                            autoReloadOnBuildDefChange,
                            bareLinRepo) = cmdOptions.get

    val mainRepo = resolveMainRepoPath(mainRepoPath)
    implicit val config: MainSettings = new MainSettings(mainRepo, configurationFile)

    exitIfGitIndexOrWorkspaceIsntClean(mainRepo)

    val projectName = mainRepo.getName
    val exercises: Seq[String] = getExerciseNames(mainRepo)

    val destinationFolder = new File(linearizedOutputFolder, projectName)

    (destinationFolder.exists(), forceDeleteExistingDestinationFolder) match {
      case (true, false) =>
        printError(s"""
                      |Destination folder ${destinationFolder.getPath} exists: Either remove this folder
                      |manually or use the '-f' command-line option to delete it automatically
                      |""".stripMargin)
      case (true, true) =>
        sbtio.delete(destinationFolder)
      case _ =>
    }

    val tmpDir = cleanMainViaGit(mainRepo, projectName)
    val cleanMainRepo = new File(tmpDir, projectName)
    printNotification(s"Cleaned main repo: $cleanMainRepo")
    val relativeCleanMainRepo = new File(cleanMainRepo, config.relativeSourceFolder)
    val linearizedProject = new File(linearizedOutputFolder, projectName)

    copyMain(cleanMainRepo, linearizedProject)

    if (config.studentTooling == StudentTooling.SBT) {
      createStudentifiedBuildFile(linearizedProject, multiJVM, isADottyProject, autoReloadOnBuildDefChange)
    }

    createBookmarkFile(config.studentifyModeClassic.studentifiedBaseFolder, linearizedProject)

    if (!bareLinRepo) {

      if(config.studentTooling == StudentTooling.SBT) {
        val templateFileList: List[String] =
          List(
            "Man.scala",
            "Navigation.scala",
            "StudentCommandsPlugin.scala",
            "StudentKeys.scala"
          )
        addSbtCommands(templateFileList, linearizedProject)
        writeStudentSettingsSBT(mainRepo, linearizedProject)
      }

      writeStudentSettings(linearizedProject)
    }
    cleanUp(List(".git", "navigation.sbt"), linearizedProject)

    removeExercisesFromCleanMain(linearizedProject, exercises)
    addGitignoreFromMain(mainRepo, linearizedProject)
    stageFirstExercise(exercises.head, relativeCleanMainRepo, linearizedProject)

    val toolSpecificFiles = config.studentTooling match {
      case StudentTooling.SBT => List(
        "project/MPSelection.scala",
        "project/Man.scala",
        "project/Navigation.scala",
        "project/SSettings.scala",
        "project/StudentCommandsPlugin.scala",
        "project/StudentKeys.scala")

      // TODO, figure out what to put here for the detached case
      case _ => Nil
    }

    val cmtFileList: List[String] =
      List(
        ".courseName",
        ".bookmark"
      ) ++ toolSpecificFiles

    if (bareLinRepo) deleteCMTConfig(cmtFileList, linearizedProject)
    initializeGitRepo(linearizedProject)
    commitFirstExercise(exercises.head, linearizedProject)
    commitRemainingExercises(exercises.tail, cleanMainRepo, linearizedProject)
    if( Helpers.getStudentifiedBranchName(linearizedProject) != "main")
      renameMainBranch(linearizedProject)

    sbtio.delete(tmpDir)
  }
}
