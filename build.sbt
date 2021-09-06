description := "Common build configuration for SBT projects"

ThisBuild / sbtVersion := "1.3.13"
ThisBuild / scalaVersion := "2.12.12"

ThisBuild / githubOwner := "precog"
ThisBuild / githubRepository := "sbt-precog"
ThisBuild / githubTokenSource := TokenSource.Environment("GITHUB_TOKEN")

lazy val root = project
  .in(file("."))
  .aggregate(core, artifact, plugin)
  .settings(name := "sbt-precog-root")
  .settings(noPublishSettings)

lazy val core = project.in(file("core"))
  .settings(name := "sbt-precog-core")

lazy val artifact = project.in(file("artifact"))
  .dependsOn(core)
  .settings(name := "sbt-precog")

lazy val plugin = project.in(file("plugin"))
  .dependsOn(core)
  .settings(name := "sbt-precog-plugin")

ThisBuild / homepage := Some(url("https://github.com/precog/sbt-precog"))
ThisBuild / scmInfo := Some(ScmInfo(homepage.value.get, "scm:git@github.com:precog/sbt-precog.git"))

ThisBuild / githubWorkflowDependencyPatterns ++= Seq("core/**/*.scala", "plugin/**/*.scala")
