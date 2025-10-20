import Dependencies.*

ThisBuild / scalaVersion := "3.4.2"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / scalaBinaryVersion := "3"


lazy val root = (project in file("."))
  .settings(
    name := "code-metrics-analyzer",

    libraryDependencies ++=
      testBundle ++
        akkaBundle ++
        uiBundle ++
        loggingBundle ++
        miscBundle,

    ThisBuild / scalafmtOnCompile := true,


    Test / parallelExecution := false
  )


enablePlugins(CucumberPlugin)
CucumberPlugin.glues := List("testLecture/code/e3bdd/steps")
