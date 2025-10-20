// Cucumber plugin per test BDD
addSbtPlugin("com.waioeka.sbt" % "cucumber-plugin" % "0.3.1")
//  Interfaccia JUnit 5 (necessaria per i test Jupiter)
addSbtPlugin("net.aichler" % "sbt-jupiter-interface" % "0.9.0")
// Visualizzare la dependency tree con: sbt dependencyTree
addDependencyTreePlugin
// Format automatico
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")
// Assembly per creare un fat-JAR eseguibile
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
// Scalafix per refactoring semantici (disattivabile)
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")
