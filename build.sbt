name := "protoscalapb"

version := "0.1"

scalaVersion := "2.12.10"

libraryDependencies ++= Seq(
<<<<<<< HEAD
"org.apache.spark" %% "spark-core" % "3.0.1" % "provided",
"org.apache.spark" %% "spark-sql" % "3.0.1" % "provided",
=======
>>>>>>> 7abdbf5fa1a276b9424ed9258ad9cf371ef51cae
"com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
"com.thesamet.scalapb" %% "sparksql-scalapb" % "0.11.0-RC1",
"com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1",
"io.github.scalapb-json" %% "scalapb-circe" % "0.11.1",
"org.json4s" %% "json4s-jackson" % "3.2.11"
<<<<<<< HEAD
=======
)
>>>>>>> 7abdbf5fa1a276b9424ed9258ad9cf371ef51cae

dependencyOverrides += "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.0-M4"

)
dependencyOverrides += "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.0-M4"

assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("com.google.protobuf.**" -> "shadeproto.@1").inAll,
  ShadeRule.rename("scala.collection.compat.**" -> "shadecompat.@1").inAll
)

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value / "scalapb"
)

