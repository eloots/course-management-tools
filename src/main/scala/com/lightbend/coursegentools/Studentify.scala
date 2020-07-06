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
    import sbt.io.{ IO => sbtio }

    implicit val exitOnFirstError: ExitOnFirstError = ExitOnFirstError(true)

    val cmdOptions = StudentifyCmdLineOptParse.parse(args)
    if (cmdOptions.isEmpty) System.exit(-1)
    val StudentifyCmdOptions(mainRepo,
                             targetFolder,
                             multiJVM,
                             firstOpt,
                             lastOpt,
                             selectedFirstOpt,
                             configurationFile,
                             useConfigureForProjects,
                             initAsGitRepo,
                             isADottyProject,
                             autoReloadOnBuildDefChange
    ) = cmdOptions.get

    exitIfGitIndexOrWorkspaceIsntClean(mainRepo)
    val projectName = mainRepo.getName
    val targetCourseFolder = new File(targetFolder, projectName)

    val tmpDir = cleanMainViaGit(mainRepo, projectName)
    val cleanMainRepo = new File(tmpDir, projectName)

    implicit val config: MainSettings = new MainSettings(mainRepo, configurationFile)

    val exercises: Seq[String] = getExerciseNames(cleanMainRepo, Some(mainRepo))

    val selectedExercises: Seq[String] = getSelectedExercises(exercises, firstOpt, lastOpt)
    println(s"""Processing exercises:
               |${selectedExercises.mkString("    ", "\n    ", "")}
       """.stripMargin)
    val initialExercise = getInitialExercise(selectedFirstOpt, selectedExercises)
    val sbtStudentCommandsTemplateFolder = new File("sbtStudentCommands")
    stageFirstExercise(initialExercise,
                       new File(cleanMainRepo, config.relativeSourceFolder),
                       targetCourseFolder
    )
    copyMain(cleanMainRepo, targetCourseFolder)
    hideExerciseSolutions(targetCourseFolder, selectedExercises)
    createBookmarkFile(initialExercise, targetCourseFolder)
    createSbtRcFile(targetCourseFolder)
    createStudentifiedBuildFile(targetCourseFolder,
                                multiJVM,
                                isADottyProject,
                                autoReloadOnBuildDefChange
    )
    addSbtCommands(sbtStudentCommandsTemplateFolder, targetCourseFolder)
    loadStudentSettings(mainRepo, targetCourseFolder)
    cleanUp(config.studentifyFilesToCleanUp, targetCourseFolder)
    sbtio.delete(tmpDir)
    if (initAsGitRepo) initialiseAsGit(mainRepo, targetCourseFolder)
  }

  def initialiseAsGit(mainRepo: File, studentifiedRepo: File): Unit = {
    import ProcessDSL._

    printNotification("Initialising studentified project as a git repository")
    Helpers.addGitignoreFromMain(mainRepo, studentifiedRepo)
    s"git init"
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(toConsoleRed(s"'git init' failed on ${studentifiedRepo.getAbsolutePath}"))
    s"git add -A"
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(
        toConsoleRed(s"'Failed to add initial file-set on ${studentifiedRepo.getAbsolutePath}")
      )
    s"""git commit -m "Initial commit""""
      .toProcessCmd(workingDir = studentifiedRepo)
      .runAndExitIfFailed(
        toConsoleRed(s"'Initial commit failed on ${studentifiedRepo.getAbsolutePath}")
      )
    Helpers.renameMainBranch(studentifiedRepo)
  }
}
