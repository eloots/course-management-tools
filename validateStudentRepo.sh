#!/bin/bash

set -euo pipefail
IFS=$'\n\t'

if [ -z ${1+x} ]; then
  echo "validateStudentRepo: Missing Parameter"
  echo "usage: validateStudentRepo [directory]"
  exit 1
fi

startingDir=`pwd`
studentRepo=$1

GREEN='\033[0;32m'
RED='\033[0;31m'
RESET='\033[0m' # No Color
SEPARATOR="##########################################################"

function validateNextExercise {    


    if ! sbt ";pullSolution;test"; then
        fail
    fi
    echo -e "[${GREEN}SUCCESS${RESET}] Validated Exercise"

    echo $SEPARATOR
    if sbt nextExercise 2>&1 | grep "Moved to"; then
        return 0
    else
        return 1
    fi
}

function validateAllExercises {
    echo $SEPARATOR
    echo Validating Exercises in $studentRepo
    cd $studentRepo

    echo $SEPARATOR
    sbt "gotoFirstExercise" 2>&1 | grep "Moved to first exercise in course"

    while validateNextExercise 
    do
        echo $SEPARATOR
    done
    
    echo $SEPARATOR
    sbt "gotoFirstExercise" 2>&1 | grep "Moved to first exercise in course"
    
    echo $SEPARATOR
    echo "Validation Completed"

    cd $startingDir
}

function fail {
    echo $SEPARATOR
    echo -e "[${RED}FAILURE${RESET}] EXITING"
    
    exit 1
}

validateAllExercises
