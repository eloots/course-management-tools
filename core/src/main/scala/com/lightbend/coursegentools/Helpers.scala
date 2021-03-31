package com.lightbend.coursegentools

import sbt.io.{IO => sbtio}

import java.io.{File, FileOutputStream}
import java.io.File.{separatorChar => sep}
import java.util.Properties
import scala.io.Source
import scala.sys.process.Process
import scala.util.Using

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

object Helpers {

  import ProcessDSL._

  def fileList(base: File): Vector[File] = {
    @scala.annotation.tailrec
    def fileList(filesSoFar: Vector[File], folders: Vector[File]): Vector[File] = {
      val subs = (folders foldLeft Vector.empty[File]) {
        case (tally, folder) =>
          tally ++ sbtio.listFiles(folder)
      }
      subs.partition(_.isDirectory) match {
        case (rem, result) if rem.isEmpty => filesSoFar ++ result
        case (rem, tally) => fileList(filesSoFar ++ tally, rem)
      }
    }

    val (seedFolders, seedFiles) = sbtio.listFiles(base).partition(_.isDirectory)
    fileList(seedFiles.toVector, seedFolders.toVector)
  }

  def zipSolution(exFolder: File, removeOriginal: Boolean = false): Unit = {
    val fl = fileList(exFolder).map(f => (f, sbtio.relativize(exFolder.getParentFile, f).get))
    val zipFile = new File(exFolder.getParentFile, s"${exFolder.getName}.zip")
    sbtio.zip(sources = fl, outputZip = zipFile, time = None)
    if (removeOriginal) sbtio.delete(exFolder)
  }

  def cleanDestinationFolder(targetCourseFolder: File): Unit = {
    val fl = fileList(targetCourseFolder).map(f => (f, sbtio.relativize(targetCourseFolder.getParentFile, f).get))
    println(fl.mkString("\n"))
  }

  def addMainCommands(mainRepo: File)(implicit config: MainSettings, exitOnFirstError: ExitOnFirstError): Unit = {
    val relativeSourceFolder = new File(mainRepo, config.relativeSourceFolder)
    if (! relativeSourceFolder.exists()) {
      sbtio.createDirectory(relativeSourceFolder)
    } else {
      if (! relativeSourceFolder.isDirectory) {
        printError(s"ERROR: $relativeSourceFolder should be a folder")
      }
    }

    val templateFileList: List[String] =
      List(
        "MPSelection.scala",
        "Man.scala",
        "Navigation.scala",
        "StudentCommandsPlugin.scala",
        "StudentKeys.scala"
      )
    addSbtCommands(templateFileList, relativeSourceFolder)
  }

  def checkMainRepo(mainRepo: File, exercises: Vector[String], exitOnFirstError: Boolean)(implicit config: MainSettings): Unit = {
    val requiredSbtProjectFiles =
    List(
    "CommonSettings.scala",
    "AdditionalSettings.scala",
    "CompileOptions.scala",
    "Dependencies.scala",
    "Man.scala",
    "Navigation.scala",
    "MPSelection.scala",
    "StudentCommandsPlugin.scala",
    "StudentKeys.scala",
    "build.properties"
    )

    implicit val eofe: ExitOnFirstError = ExitOnFirstError(exitOnFirstError)

    var numberOfErrors = 0

    val relativeSourceFolder = new File(mainRepo, config.relativeSourceFolder)
    val sbtProjectFolder = new File(relativeSourceFolder, "project")

    if (! new File(relativeSourceFolder, ".courseName").exists()) {
      printError(s"ERROR: missing .courseName file in project root folder")
      numberOfErrors += 1
    } else {
      if (sbtio.readLines(new File(relativeSourceFolder, ".courseName")).isEmpty) {
        printError(s"ERROR: .courseName file in project folder should be non-empty")
        numberOfErrors += 1
      }
    }

    if (! new File(relativeSourceFolder, "README.md").exists()) {
      printError(s"ERROR: missing README.md file in project root folder")
      numberOfErrors += 1
    }

    if (! new File(relativeSourceFolder, "common").exists() && config.commonProjectEnabled) {
      printError(s"ERROR: missing project 'common'")
      numberOfErrors += 1
    }

    if (! new File(relativeSourceFolder, "build.sbt").exists) {
      printError(s"ERROR: missing build.sbt file")
      numberOfErrors += 1
    }

    if (! sbtProjectFolder.exists()) {
      printError(s"ERROR: missing sbt 'project' folder")
      numberOfErrors += 1
    }

    for { pfile <- requiredSbtProjectFiles } {
      if ( ! new File(sbtProjectFolder, pfile).exists()) {
        printError(s"ERROR: missing file: project/$pfile")
        numberOfErrors += 1
      }
    }

    val readmeLocationPrefix = if (config.readmeInTestResources) "src" + sep + "test" + sep + "resources" else ""

    def getReadmeFile(projectFolder: File)(implicit config: MainSettings): File = {
      if (config.readmeInTestResources)
        new File(projectFolder, readmeLocationPrefix + sep + "README.md")
      else
        new File(projectFolder, "README.md")
    }

    val exerciseList = if (config.commonProjectEnabled) "common" +: exercises else exercises

    // Check all required README files are present
    for { project <-  exerciseList} {
      val projectFolder = new File(relativeSourceFolder, project)
      val readmeFile = getReadmeFile(projectFolder)
      if (! readmeFile.exists()) {
        printError(s"ERROR: missing README.md file in folder '${project}$sep$readmeLocationPrefix'")
        numberOfErrors += 1
      }
      else
        if (sbtio.readLines(readmeFile).isEmpty) {
          printError(s"ERROR: README.md file in folder '${project}$sep$readmeLocationPrefix' should have at least one line")
          numberOfErrors += 1
        }
    }

    if (numberOfErrors == 0)
      printNotification(s"No issues found in main project")
    else
      printError(s"${numberOfErrors} issues found in main project")

  }

  def putBackToMain(mainRepo: File, linearizedRepo: File, exercisesAndSHAs: Vector[ExerciseNameAndSHA])(implicit config: MainSettings): Unit = {

    val mainRepoRelative = new File(mainRepo, config.relativeSourceFolder)

    for (ExerciseNameAndSHA(exercise, sha) <- exercisesAndSHAs) {
      s"git checkout $sha"
        .toProcessCmd(linearizedRepo)
        .runAndExitIfFailed(toConsoleRed(s"Unable to checkout commit($sha) corresponding to exercise: $exercise"))

      sbtio.delete(new File(mainRepoRelative, exercise))
      sbtio.copyDirectory(new File(linearizedRepo, config.studentifyModeClassic.studentifiedBaseFolder), new File(mainRepoRelative, exercise), preserveLastModified = true)
    }

    s"git checkout main".toProcessCmd(linearizedRepo).runAndExitIfFailed(toConsoleRed(s"Unable to checkout main in linearized repo"))
  }

  def getExercisesAndSHAs(linearizedOutputFolder: File): Vector[ExerciseNameAndSHA] = {
    def convertToExNameAndSHA(v: Vector[String]): ExerciseNameAndSHA = {
      v match {
        case sha +: name +: _ => ExerciseNameAndSHA(name, sha)
      }
    }
    def splitSHAandExName(shaAndExname: String): Vector[String] = {
      shaAndExname.split("""\s+""").toVector
    }

    Process(Seq("git", "log", "--oneline"), linearizedOutputFolder).!!
      .split("""\n""").toVector
      .map(splitSHAandExName)
      .map(convertToExNameAndSHA)
      .reverse
  }

  def checkReposMatch(exercisesInMain: Vector[String], exercisesAndSHAs: Vector[ExerciseNameAndSHA])(implicit eofe: ExitOnFirstError): Unit = {
    if (exercisesInMain != exercisesAndSHAs.map(_.exName))
      printError(s"Repos are incompatible")
  }

  def commitRemainingExercises(exercises: Seq[String], mainRepo: File, linearizedProject: File)(implicit config: MainSettings): Unit = {
    val exercisesDstFolder = new File(linearizedProject, config.studentifyModeClassic.studentifiedBaseFolder)
    val mainRepoRelative = new File(mainRepo, config.relativeSourceFolder)
    for { exercise <- exercises } {
      val from = new File(mainRepoRelative, exercise)
      sbtio.delete(exercisesDstFolder)
      sbtio.copyDirectory(from, exercisesDstFolder, preserveLastModified = true)

      s"git add -A"
        .toProcessCmd(workingDir = linearizedProject)
        .runAndExitIfFailed(toConsoleRed(s"Failed to add exercise files for exercise $exercise"))

      s"git commit -m $exercise"
        .toProcessCmd(workingDir = linearizedProject)
        .runAndExitIfFailed(toConsoleRed(s"Failed to add exercise files for exercise $exercise"))
     }
  }

  def renameMainBranch(gitProject: File): Unit = {
    s"git branch -m master main"
      .toProcessCmd(workingDir = gitProject)
      .runAndExitIfFailed(toConsoleRed(s"'git rename branch' failed on ${gitProject.getAbsolutePath}"))
  }

  def getStudentifiedBranchName(studentifiedRepo: File): String = {
    val cmd = s"""git rev-parse --abbrev-ref HEAD""".toProcessCmd(workingDir = studentifiedRepo)
    Process(cmd.cmd, cmd.workingDir).!!.trim()
  }

  def commitFirstExercise(exercise: String, linearizedProject: File): Unit = {
    s"git add -A"
      .toProcessCmd(workingDir = linearizedProject)
      .runAndExitIfFailed(toConsoleRed(s"Failed to add first exercise files"))

    s"git commit -m $exercise"
      .toProcessCmd(linearizedProject)
      .runAndExitIfFailed(toConsoleRed(s"Failed to commit exercise $exercise files"))
  }

  def initializeGitRepo(linearizedProject: File): Unit = {
    s"git init"
      .toProcessCmd(linearizedProject)
      .runAndExitIfFailed(toConsoleRed(s"Failed to initialize linearized git repository in ${linearizedProject.getPath}"))
  }

  def addGitignoreFromMain(mainRepo: File, linearizedProject: File): Unit = {
    val gitignoreFile = new File(mainRepo, ".gitignore")
    if (gitignoreFile.exists())
      sbtio.copyFile(gitignoreFile, new File(linearizedProject, ".gitignore"))
  }

  def removeExercisesFromCleanMain(cleanMainRepo: File, exercises: Seq[String])(implicit eofe: ExitOnFirstError): Unit = {
    for {
      exercise <- exercises
    } {
      val exerciseFolder = new File(cleanMainRepo, exercise)
      if (exerciseFolder.exists()) {
        sbtio.delete(exerciseFolder)
      } else
        printError(s"Error in removeExercisesFromCleanMain, bailing out")
    }
  }

  def cleanMainViaGit(srcFolder: File, projectName: String)(implicit config: MainSettings): File = {
    val tmpDir = sbtio.createTemporaryDirectory
    val curDir = new File(System.getProperty("user.dir"))
    Process(Seq(config.helperScript, "cpCleanViaGit", srcFolder.getPath, tmpDir.getPath, projectName), curDir).!
    tmpDir
  }

  def isExerciseFolder(folder: File)(implicit config: MainSettings): Boolean = {

    val ExerciseNameSpec = s""".*[/\\\\]${config.exerciseProjectPrefix}_\\d{3}_\\w+$$""".r

    ExerciseNameSpec.findFirstIn(folder.getPath).isDefined
  }

  def copyMain(mainRepo: File, targetFolder: File)(implicit config: MainSettings): Unit = {
    val relativeSourceFolder = new File(mainRepo, config.relativeSourceFolder)
    sbtio.copyDirectory(relativeSourceFolder, targetFolder, overwrite = false, preserveLastModified = true)
  }

  def getExerciseNames(cleanMainRepo: File, mainRepo: Option[File] = None)(implicit config: MainSettings): Vector[String] = {
    val relativeSourceFolder = new File(cleanMainRepo, config.relativeSourceFolder)
    val exerciseFolders = sbtio.listFiles(relativeSourceFolder, FoldersOnly()).filter(isExerciseFolder)
    val exercisesNames = exerciseFolders.map(folder => folder.getName).toVector.sorted
    if (exercisesNames.isEmpty) {
      val repo = mainRepo.getOrElse(cleanMainRepo)
      println(s"${Console.RED}ERROR: No exercises found in ${new File(repo, config.relativeSourceFolder)}${Console.RESET}")
      System.exit(-1)
    }
    exercisesNames
  }

  def hideExerciseSolutions(targetFolder: File, selectedExercises: Seq[String])(implicit config: MainSettings): Unit = {
    val hiddenFolder = new File(targetFolder, config.solutionsFolder)
    sbtio.createDirectory(hiddenFolder)
    val exercises = sbtio.listFiles(targetFolder, FoldersOnly()).filter(isExerciseFolder)
    exercises.foreach { exercise =>
      if (selectedExercises contains exercise.getName) {
        val exerciseFolder = new File(hiddenFolder, exercise.getName)
        sbtio.move(exercise, exerciseFolder)
        zipSolution(exerciseFolder, removeOriginal = true)
      } else {
        sbtio.delete(exercise)
      }
    }
  }

  def getInitialExercise(selectedFirstOpt: Option[String], selectedExercises: Seq[String])(implicit eofe: ExitOnFirstError): String = {
    val selectedExercise = selectedFirstOpt.getOrElse(selectedExercises.head)
    if (selectedExercises contains selectedExercise)
      selectedExercise
    else {
      printError(s"Exercise on start not in selected range of exercises")
      selectedExercise
    }
  }
  def stageFirstExercise(firstEx: String, mainRepo: File, targetFolder: File)(implicit config: MainSettings): Unit = {
    val firstExercise = new File(mainRepo, firstEx)
    sbtio.copyDirectory(firstExercise, new File(targetFolder, config.studentifyModeClassic.studentifiedBaseFolder), preserveLastModified = true)
  }

  def createBookmarkFile(firstExercise: String, targetFolder: File): Unit = {
    println(s"Setting student repository bookmark to $firstExercise")
    dumpStringToFile(firstExercise, new File(targetFolder, ".bookmark").getPath)
  }

  def createSbtRcFile(targetFolder: File)(implicit config: MainSettings): Unit = {
    dumpStringToFile(s"alias boot = ;reload ;project ${config.studentifyModeClassic.studentifiedBaseFolder} ;iflast shell", new File(targetFolder, ".sbtrc").getPath)
  }

  def deleteCMTConfig(templateFileList: List[String], targetCourseFolder: File): Unit = {
    for {
      templateFile <- templateFileList
      file = new File(targetCourseFolder, templateFile)
    } sbtio.delete(file)
  }

  def addSbtCommands(templateFileList: List[String], targetCourseFolder: File): Unit = {
    val projectFolder = new File(targetCourseFolder, "project")
    for {
      templateFile <- templateFileList
      template = Source.fromInputStream(this.getClass().getClassLoader().getResourceAsStream(templateFile + ".template"))
      templateContent = try template.mkString finally template.close()
    } dumpStringToFile(templateContent, new File(projectFolder, templateFile).getPath)
  }

  def getSelectedExercises(exercises: Seq[String], firstOpt: Option[String], lastOpt: Option[String])(implicit eofe: ExitOnFirstError): Seq[String] = {
    val (firstExercise, lastExercise) = (exercises.head, exercises.last)
    val selExcs = (firstOpt.getOrElse(exercises.head), lastOpt.getOrElse(exercises.last)) match {
      case (`firstExercise`, `lastExercise`) =>
        exercises
      case (first, last) =>
        exercises.dropWhile(_ != first).reverse.dropWhile(_ != last).reverse

    }
    if (selExcs.isEmpty) printError(s"Invalid exercise selection")
    selExcs
  }

  def duplicateExercise(mainRepo: File,
                        exercise: String,
                        exerciseNr: Int)(implicit config: MainSettings): Unit = {

    val relativeSourceFolder = new File(mainRepo, config.relativeSourceFolder)
    val newExercise =   renumberExercise(exercise, exerciseNr) + "_copy"
    sbtio.copyDirectory(new File(relativeSourceFolder, exercise), new File(relativeSourceFolder, newExercise), preserveLastModified = true)
  }

  def shiftExercisesUp(mainRepo: File,
                       exercises: Vector[String],
                       startFromExerciseNumber: Int,
                       exerciseNumbers: Vector[Int])(implicit config: MainSettings): Unit = {

    val relativeSourceFolder = new File(mainRepo, config.relativeSourceFolder)
    val exercisesToShift = exercises.dropWhile(exercise => extractExerciseNr(exercise) != startFromExerciseNumber)
    val moves = for {
      exercise <- exercisesToShift
      oldExDir = new File(relativeSourceFolder, exercise)
      newExDir = new File(relativeSourceFolder, renumberExercise(exercise, extractExerciseNr(exercise) + 1))
    } yield (oldExDir, newExDir)
    sbtio.move(moves)
  }


  def commonProjectName(implicit config: MainSettings): String =
    if (config.commonProjectEnabled)
      """
        |    common,""".stripMargin
    else
      ""

  def commonProjectDef(implicit config: MainSettings): String =
    if (config.commonProjectEnabled)
      """
        |lazy val common = project
        |  .settings(CommonSettings.commonSettings: _*)
        |""".stripMargin
    else
      ""

  def commonProjectDefMJvm(implicit config: MainSettings): String =
    if (config.commonProjectEnabled)
      """
        |lazy val common = project
        |  .settings(SbtMultiJvm.multiJvmSettings: _*)
        |  .settings(CommonSettings.commonSettings: _*)
        |  .configs(MultiJvm)
        |""".stripMargin
    else
      ""

  def dependsOnCommon(implicit config: MainSettings): String =
    if (config.commonProjectEnabled)
      """
        |  .dependsOn(common % "test->test;compile->compile")""".stripMargin
    else
      ""

  def createMainBuildFile(exercises: Seq[String],
                          mainRepo: File,
                          multiJVM: Boolean,
                          isADottyProject: Boolean,
                          autoReloadOnBuildDefChange: Boolean
                           )(implicit config: MainSettings): Unit = {

    val targetFolder = new File(mainRepo, config.relativeSourceFolder)

    val exercisesBackTicked = exercises.map(exrc => s"`$exrc`")

    val setScalaVersion = scalaDottyVersion(isADottyProject)

    val commonProjectDefMJvm =
      if (config.commonProjectEnabled)
        """
          |lazy val common = project
          |  .settings(SbtMultiJvm.multiJvmSettings: _*)
          |  .settings(CommonSettings.commonSettings: _*)
          |  .configs(MultiJvm)
          |""".stripMargin
      else
        ""

    def exerciseDep(exercise: String): String = {
      if (config.useConfigureForProjects) {
        s"""lazy val $exercise = project
           |  .configure(CommonSettings.configure)${dependsOnCommon}${config.exercisePreamble}""".stripMargin
      } else {
        s"""lazy val $exercise = project
           |  .settings(CommonSettings.commonSettings: _*)${dependsOnCommon}${config.exercisePreamble}""".stripMargin
      }
    }

    def exerciseMJvmDep(exercise: String): String = {
      s"""lazy val $exercise = projectG
         |  .settings(SbtMultiJvm.multiJvmSettings: _*)
         |  .settings(CommonSettings.commonSettings: _*)${dependsOnCommon}
         |  .configs(MultiJvm)""".stripMargin
    }
    val exerciseList = exercisesBackTicked.mkString(",\n    ")

    val buildDefinition = if (multiJVM) {
      s"""/***************************************************************
         |  *      THIS IS A GENERATED FILE - EDIT AT YOUR OWN RISK      *
         |  **************************************************************
         |  *
         |  * Use the mainadm command to generate a new version of
         |  * this build file.
         |  *
         |  * See https://github.com/lightbend/course-management-tools
         |  * for more details
         |  *
         |  */
         |
         |import sbt._
         |${reloadBuildDefOnChange(autoReloadOnBuildDefChange)}
         |lazy val `${config.mainBaseProjectName}` = (project in file("."))
         |  .aggregate(${commonProjectName}
         |    $exerciseList
         |  )${setScalaVersion}
         |  .settings(SbtMultiJvm.multiJvmSettings: _*)
         |  .settings(CommonSettings.commonSettings: _*)
         |  .configs(MultiJvm)
         |${commonProjectDefMJvm}
         |${exercisesBackTicked.map{ exrc => exerciseMJvmDep(exrc)}.mkString("\n\n")}
       """.stripMargin
    } else {
      s"""/***************************************************************
         |  *      THIS IS A GENERATED FILE - EDIT AT YOUR OWN RISK      *
         |  **************************************************************
         |  *
         |  * Use the mainadm command to generate a new version of
         |  * this build file.
         |  *
         |  * See https://github.com/lightbend/course-management-tools
         |  * for more details
         |  *
         |  */
         |
         |import sbt._
         |${reloadBuildDefOnChange(autoReloadOnBuildDefChange)}
         |lazy val `${config.mainBaseProjectName}` = (project in file("."))
         |  .aggregate(${commonProjectName}
         |    $exerciseList
         |  )${setScalaVersion}
         |  .settings(CommonSettings.commonSettings: _*)
         |${commonProjectDef}
         |${exercisesBackTicked.map{ exrc => exerciseDep(exrc)}.mkString("\n\n")}
       """.stripMargin
    }
    dumpStringToFile(buildDefinition, new File(targetFolder, "build.sbt").getPath)

  }

  def scalaDottyVersion(isADottyProject: Boolean): String = {
    if (isADottyProject) """
        |  .settings(ThisBuild / scalaVersion := Version.scalaVersion)""".stripMargin
    else
      ""
  }

  def reloadBuildDefOnChange(autoReloadOnBuildDefChange: Boolean): String = {
    if (autoReloadOnBuildDefChange)
      """
        |Global / onChangedBuildSource := ReloadOnSourceChanges
        |""".stripMargin
    else
      ""
  }

  def setPromptInBareLinearizedRepo(implicit config: MainSettings): String = {
    s"""
       |Global / onLoad := {
       |  (Global / onLoad).value andThen (state => "project ${config.studentifyModeClassic.studentifiedBaseFolder}" :: state)
       |}
       |""".stripMargin
  }

  def createStudentifiedBuildFile(targetFolder: File, multiJVM: Boolean,
                                  isADottyProject: Boolean,
                                  autoReloadOnBuildDefChange: Boolean)
                                 (implicit config: MainSettings): Unit = {
    val setScalaVersion = scalaDottyVersion(isADottyProject)
    val buildDef =
      if (config.commonProjectEnabled)
        s"""
           |${reloadBuildDefOnChange(autoReloadOnBuildDefChange)}
           |${setPromptInBareLinearizedRepo}
           |lazy val `${config.studentifiedProjectName}` = (project in file("."))
           |  .aggregate(${commonProjectName}
           |    `${config.studentifyModeClassic.studentifiedBaseFolder}`
           |  )${setScalaVersion}
           |  .settings(CommonSettings.commonSettings: _*)
           |${commonProjectDef}
           |lazy val `${config.studentifyModeClassic.studentifiedBaseFolder}` = project
           |  ${if (config.useConfigureForProjects)
                s".configure(CommonSettings.configure)${config.exercisePreamble}${dependsOnCommon}"
                else s".settings(CommonSettings.commonSettings: _*)${config.exercisePreamble}${dependsOnCommon}"
              }
           """.stripMargin
      else {
        val sbf = config.studentifyModeClassic.studentifiedBaseFolder
        s"""
           |Global / onChangedBuildSource := ReloadOnSourceChanges
           |
           |Global / onLoad := {
           |  (Global / onLoad).value andThen (state => "project $sbf" :: state)
           |}
           |
           |lazy val `$sbf` = (project in file("$sbf"))${setScalaVersion}
           |  ${if (config.useConfigureForProjects)
                s".configure(CommonSettings.configure)${config.exercisePreamble}${dependsOnCommon}"
                else s".settings(CommonSettings.commonSettings: _*)${config.exercisePreamble}${dependsOnCommon}"
              }
           |
           |""".stripMargin
      }

    val mJvmBuildDef =
      if (config.commonProjectEnabled)
        s"""${reloadBuildDefOnChange(autoReloadOnBuildDefChange)}
           |${setPromptInBareLinearizedRepo}
           |import sbt._
           |
           |lazy val `${config.studentifiedProjectName}` = (project in file("."))
           |  .aggregate(${commonProjectName}
           |    `${config.studentifyModeClassic.studentifiedBaseFolder}`
           |  )${setScalaVersion}
           |  .settings(SbtMultiJvm.multiJvmSettings: _*)
           |  .settings(CommonSettings.commonSettings: _*)
           |  .configs(MultiJvm)
           |${commonProjectDefMJvm}
           |lazy val `${config.studentifyModeClassic.studentifiedBaseFolder}` = project
           |  .settings(SbtMultiJvm.multiJvmSettings: _*)
           |  ${if (config.useConfigureForProjects) ".settings(CommonSettings.commonSettings: _*)" else ".configure(CommonSettings.configure)"}
           |  .configs(MultiJvm)${dependsOnCommon}
           |
         """.stripMargin
      else {
        val sbf = config.studentifyModeClassic.studentifiedBaseFolder
        s"""
           |Global / onChangedBuildSource := ReloadOnSourceChanges
           |
           |Global / onLoad := {
           |  (Global / onLoad).value andThen (state => "project $sbf" :: state)
           |}
           |
           |lazy val `$sbf` = (project in file("$sbf"))${setScalaVersion}
           |  .settings(SbtMultiJvm.multiJvmSettings: _*)
           |  ${if (config.useConfigureForProjects)
                s".configure(CommonSettings.configure)${config.exercisePreamble}${dependsOnCommon}"
                else s".settings(CommonSettings.commonSettings: _*)${config.exercisePreamble}${dependsOnCommon}"
              }
           |  .configs(MultiJvm)
           |
           |""".stripMargin
      }

      if (multiJVM) {
        dumpStringToFile(mJvmBuildDef, new File(targetFolder, "build.sbt").getPath)
      } else {
        dumpStringToFile(buildDef, new File(targetFolder, "build.sbt").getPath)
      }
  }

  def cleanUp(files: Seq[String], targetFolder: File): Unit = {
    for (file <- files) {
      sbtio.delete(new File(targetFolder, file))
    }
  }

  def exitIfGitIndexOrWorkspaceIsntClean(mainRepo: File)(implicit config: MainSettings): Unit = {
    """git diff-index --quiet HEAD --"""
      .toProcessCmd(workingDir = mainRepo)
      .runAndExitIfFailed(toConsoleRed(s"YOU HAVE UNCOMMITTED CHANGES IN YOUR GIT INDEX. COMMIT CHANGES AND RE-RUN THE COMMAND"))

    s"""${config.helperScript} checkWorkspace ${mainRepo.getPath}"""
      .toProcessCmd(workingDir = new File("."))
      .runAndExitIfFailed(toConsoleRed(s"YOU HAVE CHANGES IN YOUR GIT WORKSPACE. COMMIT CHANGES AND RE-RUN STUDENTIFY"))
  }

  def printErrorMsgAndExit(mainConfigurationFile: File, lineNr: Option[Int], setting: String): Unit = {
    val LineNrInfo = if (lineNr.isDefined) s"on line ${lineNr.get+1}" else ""
    println(
      s"""Invalid setting syntax $LineNrInfo in ${mainConfigurationFile.getName}:
         |  $setting
               """.stripMargin)
    System.exit(-1)
  }

  def writeStudentSettings(targetFolder: File)(implicit mainSettings: MainSettings): Unit = {
    val props = new Properties()
    props.put("cmt.exercise-globs", mainSettings.testCodeFolders.map(_ + "/**").mkString(";"))
    props.put("cmt.solution-globs", "**")
    props.put("cmt.studentified-base-folder", mainSettings.studentifyModeClassic.studentifiedBaseFolder)
    props.put("cmt.readme-in-test-resources", mainSettings.readmeInTestResources.toString)
    Using(new FileOutputStream(new File(targetFolder, ".student-settings").getPath)) { os =>
        props.store(os, "Generated by studentify")
    }
  }

  // This should eventually be replaced by the properties file generated above.
  def writeStudentSettingsSBT(mainRepo: File, targetFolder: File)(implicit mainSettings: MainSettings): Unit = {
    dumpStringToFile(
      s"""package sbtstudent
         |
         |object SSettings {
         |  import scala.Console._
         |  val consoleColorMap: Map[String, String] =
         |    Map("RESET" -> RESET, "GREEN" -> GREEN, "RED" -> RED, "BLUE" -> BLUE, "CYAN" -> CYAN, "YELLOW" -> YELLOW, "WHITE" -> WHITE, "BLACK" -> BLACK, "MAGENTA" -> MAGENTA)
         |
         |  val testFolders = List(${mainSettings.testCodeFolders.map(s => s""""$s"""").mkString(", ")})
         |
         |  val studentifiedBaseFolder = "${mainSettings.studentifyModeClassic.studentifiedBaseFolder}"
         |
         |  val readmeInTestResources: Boolean = ${mainSettings.readmeInTestResources}
         |
         |  val promptManColor         = "${mainSettings.Colors.promptManColor}"
         |  val promptExerciseColor    = "${mainSettings.Colors.promptExerciseColor}"
         |  val promptCourseNameColor = "${mainSettings.Colors.promptCourseNameColor}"
         |}
       """.stripMargin, new File(targetFolder, "project/SSettings.scala").getPath)
  }

  def resolveMainRepoPath(mainRepo: File): File = {
    if (mainRepo.getPath == ".") {
      val repoPath = getRepoPathFromGit()
      new File(repoPath)
    } else {
      mainRepo
    }
  }

  def getRepoPathFromGit(): String = {
    "git rev-parse --show-toplevel"
      .toProcessCmd(workingDir = new File(".")).runAndReadOutput()
  }
}
