package com.lightbend.coursegentools

import java.io.File

object Studentify {

  def main(args: Array[String]): Unit = {

    import Helpers._
    import sbt.io.{IO => sbtio}

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
