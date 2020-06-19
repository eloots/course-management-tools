package com.lightbend.coursegentools

import java.io.File

import scala.util.Random

object GenTests {
  def generateTestScript(mainRepo: File,
                         relativeSourceFolder: String,
                         configurationFile: Option[String],
                         testScript: File,
                         exercises: Vector[String],
                         exerciseNumbers: Vector[Int],
                         isADottyProject: Boolean): Unit = {
    val mainRepoPath = mainRepo.getAbsolutePath
    val exerciseFolderPath: String =
      if (relativeSourceFolder != "") new File(mainRepo, relativeSourceFolder).getAbsolutePath else mainRepoPath

    val cmtFolder = System.getProperty("user.dir")
    val configurationFileArgument = if(configurationFile.isDefined) s"-cfg ${configurationFile.get}" else ""
    val isADottyProjectOption = if (isADottyProject) "-dot" else ""

    val script: String =
      s"""#!/bin/bash
        |
        |set -e
        |set -o pipefail
        |
        |CMT_FOLDER=$cmtFolder # This should become a script argument at some stage
        |
        |${separator("Test main repo 'man e', 'test'")}
        |cd $exerciseFolderPath
        |sbt "${genMainExerciseTestCmds(exercises)}"
        |
        |${separator("Create a temporary folder to hold studentified version")}
        |TMP_DIR=$$(mktemp -d /tmp/CMT.XXXXXX)
        |cd $$CMT_FOLDER
        |
        |${separator("Studentify the project")}
        |sbt "studentify $configurationFileArgument $isADottyProjectOption $mainRepoPath $$TMP_DIR"
        |
        |${separator("Test studentified project:\n  Exercise 'nextExercise', 'pullSolution', 'listExercises', 'man e' commands")}
        |cd $$TMP_DIR/${mainRepo.getName}
        |sbt "${genStudentifiedTestCmds(exercises)}"
        |
        |${separator("Test studentified project:\n  Exercise 'gotoExercise', 'gotoExerciseNr', 'pullSolution', 'test', 'listExercises, 'saveState', savedStates', 'restoreState''")}
        |sbt "${genGotoExerciseTestCmds(exercises, exerciseNumbers)}"
        |
        |# Clean up after ourselves
        |trap "rm -rf $$TMP_DIR" 0
      """.stripMargin

    dumpStringToFile(script, testScript.getAbsolutePath)

  }

  def separator(message: String): String = {
    s"""echo "
       |==========================================================
       |$message
       |==========================================================
       |"
     """.stripMargin
  }

  def genMainExerciseTestCmds(exercises: Vector[String]): String = {
    exercises.map(exercise => s";project $exercise;compile;test;man e").mkString("\n", "\n", "")
  }

  def genStudentifiedTestCmds(exercises: Vector[String]): String = {
    exercises.map(_ => ";listExercises;pullSolution;man e;compile;test;nextExercise").mkString("\n", "\n", "") +
      ";gotoFirstExercise;listExercises"
  }

  def genGotoExerciseTestCmds(exercises: Vector[String], exerciseNumbers: Vector[Int]): String = {
    Random.shuffle(exercises).zip(Random.shuffle(exerciseNumbers))
      .map{ case (exercise, exerciseNumber) =>
        s""";gotoExercise $exercise; pullSolution; saveState; savedStates; listExercises; test; prevExercise
           |;gotoExerciseNr $exerciseNumber; pullSolution; listExercises; test; man e; prevExercise; restoreState $exercise""".stripMargin}
      .mkString("\n", "\n", "")
  }
}
