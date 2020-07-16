libraryDependencies += "org.yaml" % "snakeyaml" % "1.26"

addSbtPlugin("io.crashbox"       % "sbt-gpg"            % "0.2.1")
addSbtPlugin("com.codecommit"    % "sbt-github-actions" % "0.8.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"         % "5.6.0")
addSbtPlugin("com.dcsobral"      % "sbt-trickle"        % "0.3-8f135be")

resolvers += Resolver.sonatypeRepo("snapshots")

val specs2Version = "4.10.0"

libraryDependencies ++= Seq(
  "org.typelevel"       %% "cats-effect"              % "2.1.4",
  "co.fs2"              %% "fs2-core"                 % "2.4.2",
  "com.47deg"           %% "github4s"                 % "0.24.1+14-df9ec5e7-SNAPSHOT",
  "org.http4s"          %% "http4s-async-http-client" % "0.21.6",
  "org.sangria-graphql" %% "sangria"                  % "2.0.0",
  "org.specs2"          %% "specs2-core"              % specs2Version % Test,
  "org.specs2"          %% "specs2-matcher-extra"     % specs2Version % Test,
  "org.specs2"          %% "specs2-scalacheck"        % specs2Version % Test)

scalacOptions += "-Ypartial-unification"

Test / testOptions += Tests.Argument("tmpdir", (target.value / "tests" / "tmp").getPath)

enablePlugins(GraphQLCodegenPlugin)

libraryDependencies ++= Seq(
  compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.0" cross CrossVersion.full),
  "com.github.ghik" % "silencer-lib" % "1.7.0" % Provided cross CrossVersion.full
)

scalacOptions += s"-P:silencer:pathFilters=${sourceManaged.value}/.*"

graphqlCodegenSchema := (Compile / resourceDirectory).value / "core" / "schema.graphql"
graphqlCodegenJson := JsonCodec.Circe
