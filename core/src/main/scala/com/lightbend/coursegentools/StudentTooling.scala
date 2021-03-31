package com.lightbend.coursegentools

sealed trait StudentTooling

object StudentTooling {
  case object SBT extends StudentTooling
  case object Detached extends StudentTooling

  val values = Map(
    "sbt" -> SBT,
    "detached" -> Detached
  )
}
