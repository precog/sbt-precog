addSbtPlugin("io.crashbox" % "sbt-gpg" % "0.2.1")
addSbtPlugin("com.codecommit" % "sbt-github-actions" % "0.13.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.2.16")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
addSbtPlugin("com.dcsobral" % "sbt-trickle" % "0.3-8f135be")

val specs2Version = "4.12.7"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.5.1",
  "co.fs2" %% "fs2-core" % "2.5.9",
  "com.47deg" %% "github4s" % "0.25.0",
  "org.http4s" %% "http4s-async-http-client" % "0.21.27",
  "org.sangria-graphql" %% "sangria" % "2.1.3",
  "org.specs2" %% "specs2-core" % specs2Version % Test,
  "org.specs2" %% "specs2-matcher-extra" % specs2Version % Test,
  "org.specs2" %% "specs2-scalacheck" % specs2Version % Test
)

scalacOptions += "-Ypartial-unification"

Test / testOptions += Tests.Argument("tmpdir", (target.value / "tests" / "tmp").getPath)

enablePlugins(GraphQLCodegenPlugin)
scalacOptions += "-Wconf:src=src_managed/.*:silent"

graphqlCodegenSchema := (Compile / resourceDirectory).value / "core" / "schema.graphql"
graphqlCodegenJson := JsonCodec.Circe
