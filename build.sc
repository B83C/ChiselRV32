// import Mill dependency
import mill._
import mill.define.Sources
import mill.modules.Util
import mill.scalalib.TestModule.ScalaTest
import scalalib._
// support BSP
import mill.bsp._

// Note: This project requires .mill-jvm-opts file containing:
//   -Dchisel.project.root=${PWD}
// This is needed because Chisel needs to know the project root directory
// to properly generate and handle test directories and output files.
// See: https://github.com/com-lihaoyi/mill/issues/3840

object `rsd_rv32` extends SbtModule { m =>
  override def millSourcePath = super.millSourcePath / os.up
  override def scalaVersion = "2.13.15"
  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit",
    "-Ymacro-annotations",
  )
  override def ivyDeps = Agg(
    ivy"org.chipsalliance::chisel:6.6.0",
    ivy"edu.berkeley.cs::chiseltest:6.0.0",
    ivy"com.github.rameloni::tywaves-chisel-api:0.4.2-SNAPSHOT",
    ivy"com.zhutmost::chipmunk:0.1-SNAPSHOT",
  )
  // val ivyRepos = Seq(
  //       ivy.IvyModule(ivy.IvyRepo("local", "file://" + System.getProperty("user.home") + "/.ivy2/local/"))
  //       )
  override def scalacPluginIvyDeps = Agg(
    ivy"org.chipsalliance:::chisel-plugin:6.6.0",
  )
  object test extends SbtTests with TestModule.ScalaTest {
    override def ivyDeps = m.ivyDeps() ++ Agg(
      ivy"edu.berkeley.cs::chiseltest::6.0.0",
      ivy"org.scalatest::scalatest::3.2.16",
      ivy"com.zhutmost::chipmunk:0.1-SNAPSHOT",
    )
  }
}
