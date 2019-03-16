name := "bitcoinj-test"

version := "0.1"

scalaVersion := "2.12.8"

val bitcoinjVersion = "0.15"

libraryDependencies ++= Seq(
  "org.bitcoinj" % "bitcoinj-core" % bitcoinjVersion % "compile"
)