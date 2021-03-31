package com.lightbend.coursegentools

import java.io.File

object Studentify {

  def main(args: Array[String]): Unit = {

    import Helpers._
    import sbt.io.{IO => sbtio}

    implicit val exitOnFirstError: ExitOnFirstError = ExitOnFirstError(true)

    val cmdOptions = StudentifyCmdLineOptParse.parse(args)
    if (cmdOptions.isEmpty) System.exit(-1)
    val StudentifyCmdOptions(mainRepoPath,
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

    val mainRepo = resolveMainRepoPath(mainRepoPath)
    implicit val config: MainSettings = new MainSettings(mainRepo, configurationFile)

    exitIfGitIndexOrWorkspaceIsntClean(mainRepo)
    val projectName = mainRepo.getName
    val targetCourseFolder = new File(targetFolder, projectName)

    val tmpDir = cleanMainViaGit(mainRepo, projectName)
    val cleanMainRepo = new File(tmpDir, projectName)

    val exercises: Seq[String] = getExerciseNames(cleanMainRepo, Some(mainRepo))

    val selectedExercises: Seq[String] = getSelectedExercises(exercises, firstOpt, lastOpt)
    println(s"""Processing exercises:
               |${selectedExercises.mkString("    ", "\n    ", "")}
       """.stripMargin)
    val initialExercise = getInitialExercise(selectedFirstOpt, selectedExercises)
    stageFirstExercise(initialExercise,
                       new File(cleanMainRepo, config.relativeSourceFolder),
                       targetCourseFolder
    )
    copyMain(cleanMainRepo, targetCourseFolder)
    hideExerciseSolutions(targetCourseFolder, selectedExercises)
    createBookmarkFile(initialExercise, targetCourseFolder)

    if(config.studentTooling == StudentTooling.SBT) {
      createSbtRcFile(targetCourseFolder)
      createStudentifiedBuildFile(targetCourseFolder,
        multiJVM,
        isADottyProject,
        autoReloadOnBuildDefChange
      )

      val templateFileList: List[String] =
        List(
          "Man.scala",
          "Navigation.scala",
          "Pssr.scala",
          "StudentCommandsPlugin.scala",
          "StudentKeys.scala",
          "Zip.scala"
        )
      addSbtCommands(templateFileList, targetCourseFolder)
      writeStudentSettingsSBT(mainRepo, targetCourseFolder)
    }
    writeStudentSettings(targetCourseFolder)
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
    if( Helpers.getStudentifiedBranchName(studentifiedRepo) != "main")
      Helpers.renameMainBranch(studentifiedRepo)
  }
}
