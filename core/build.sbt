libraryDependencies += "org.yaml" % "snakeyaml" % "1.26"

addSbtPlugin("io.crashbox"       % "sbt-gpg"            % "0.2.1")
addSbtPlugin("com.codecommit"    % "sbt-github-actions" % "0.1-58d9629")
addSbtPlugin("de.heikoseeberger" % "sbt-header"         % "5.4.0")
addSbtPlugin("com.dcsobral"      % "sbt-trickle"        % "0.2.4")

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.1.2",
  "com.47deg" %% "github4s" % "0.22.0")
