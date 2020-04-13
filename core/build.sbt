libraryDependencies += "org.yaml" % "snakeyaml" % "1.26"

addSbtPlugin("io.crashbox"       % "sbt-gpg"            % "0.2.1")
addSbtPlugin("com.codecommit"    % "sbt-github-actions" % "0.5.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"         % "5.4.0")
addSbtPlugin("com.dcsobral"      % "sbt-trickle"        % "0.3-37c1a2c")

libraryDependencies ++= Seq(
  "org.typelevel"       %% "cats-effect"       % "2.1.2",
  "co.fs2"              %% "fs2-core"          % "2.2.1",
  "com.47deg"           %% "github4s"          % "0.22.0",
  "org.sangria-graphql" %% "sangria"           % "1.4.2",
  "org.specs2"          %% "specs2-core"       % "4.8.3"   % Test,
  "org.specs2"          %% "specs2-scalacheck" % "4.8.3"   % Test)

scalacOptions += "-Ypartial-unification"

enablePlugins(GraphQLCodegenPlugin)

graphqlCodegenSchema := (Compile / resourceDirectory).value / "core" / "schema.graphql"
graphqlCodegenJson := JsonCodec.Circe
