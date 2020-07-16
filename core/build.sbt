libraryDependencies += "org.yaml" % "snakeyaml" % "1.26"

addSbtPlugin("io.crashbox"       % "sbt-gpg"            % "0.2.1")
addSbtPlugin("com.codecommit"    % "sbt-github-actions" % "0.6.4")
addSbtPlugin("de.heikoseeberger" % "sbt-header"         % "5.6.0")
addSbtPlugin("com.dcsobral"      % "sbt-trickle"        % "0.3-8f135be")

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "org.typelevel"       %% "cats-effect"              % "2.1.4",
  "co.fs2"              %% "fs2-core"                 % "2.2.1",
  "com.47deg"           %% "github4s"                 % "0.24.1+14-df9ec5e7-SNAPSHOT",
  "org.http4s"          %% "http4s-async-http-client" % "0.21.4",
  "org.sangria-graphql" %% "sangria"                  % "1.4.2",
  "org.specs2"          %% "specs2-core"              % "4.8.3"    % Test,
  "org.specs2"          %% "specs2-matcher-extra"     % "4.8.3"    % Test,
  "org.specs2"          %% "specs2-scalacheck"        % "4.8.3"    % Test)

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
