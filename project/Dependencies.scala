import sbt._

object Dependencies {

  // ==========
  // Versions
  // ==========
  object V {
    val junitJupiter = "6.0.0"
    val scalaTest = "3.2.19"
    val seleniumPlus = "3.2.12.1"
    val cucumberScala = "8.35.0"
    val scalaCheckPlus = "3.2.11.0"
    val scalaSwing = "3.0.0"
    val akka = "2.8.8"
    val logback = "1.5.19"
    val toolkit = "0.7.0"
  }

  // ==========
  // Modules
  // ==========
  // --- Test stack ---
  val junitApi = "org.junit.jupiter" % "junit-jupiter-api" % V.junitJupiter % Test
  val junitEngine = "org.junit.jupiter" % "junit-jupiter-engine" % V.junitJupiter % Test
  val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % Test
  val seleniumPlus = "org.scalatestplus" %% "selenium-4-1" % V.seleniumPlus % Test
  val cucumberScala = "io.cucumber" %% "cucumber-scala" % V.cucumberScala % Test
  val scalaCheckPlus = "org.scalatestplus" %% "scalacheck-1-15" % V.scalaCheckPlus % Test

  // --- Akka ---
  val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % V.akka
  val akkaTestkitTyped = "com.typesafe.akka" %% "akka-actor-testkit-typed" % V.akka % Test

  // --- UI / Logging / Misc ---
  val scalaSwing = "org.scala-lang.modules" %% "scala-swing" % V.scalaSwing
  val logbackClassic = "ch.qos.logback" % "logback-classic" % V.logback
  val scalaToolkit = "org.scala-lang" %% "toolkit" % V.toolkit

  // ==========
  // Bundles
  // ==========
  val testBundle: Seq[ModuleID] =
    Seq(junitApi, junitEngine, scalaTest, seleniumPlus, cucumberScala, scalaCheckPlus, akkaTestkitTyped)

  val akkaBundle: Seq[ModuleID] =
    Seq(akkaActorTyped, akkaTestkitTyped)

  val uiBundle: Seq[ModuleID] =
    Seq(scalaSwing)

  val loggingBundle: Seq[ModuleID] =
    Seq(logbackClassic)

  val miscBundle: Seq[ModuleID] =
    Seq(scalaToolkit)
}
