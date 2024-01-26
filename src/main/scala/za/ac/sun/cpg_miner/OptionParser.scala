package za.ac.sun.cpg_miner

import better.files.File
import scopt.OptionDef

import scala.util.{Failure, Success, Try}

class OptionParser[T](name: String) extends scopt.OptionParser[T](name) {

  def inCpg(): OptionDef[String, T] =
    arg[String]("input-cpg")
      .text("The input cpg to mine from.")
      .validate(x =>
        Try(File(x)) match {
          case Failure(_)                     => failure(s"'$x' is an invalid file.")
          case Success(f) if !f.isRegularFile => failure(s"'$x' is an invalid file.")
          case Success(f) if f.isDirectory    => failure(s"'$x' is a directory, it must be a file.")
          case Success(f) =>
            val ext = Set("cpg", "bin")
            if (f.extension(includeDot = false).exists(ext.contains)) success
            else failure(s"'$x' does not appear to be a valid CPG file.")
        }
      )

  def inDir(): OptionDef[String, T] =
    arg[String]("input-dir")
      .text("The input directory to generate a CPG from")
      .validate(x =>
        Try(File(x)) match {
          case Failure(_)                   => failure(s"'$x' is an invalid directory.")
          case Success(f) if !f.isDirectory => failure(s"'$x' is an invalid directory.")
          case Success(_)                   => success
        }
      )

  def outDir(): OptionDef[String, T] =
    opt[String]('o', "output-dir")
      .text("The output directory to dump the mined artifacts.")
      .validate(x =>
        Try(File(x)) match {
          case Failure(_) => failure(s"'$x' is an invalid directory.")
          case Success(f) if !f.exists =>
            f.createDirectoryIfNotExists(createParents = true)
            success
          case Success(_) => success
        }
      )

  def methodName(): OptionDef[String, T] =
    opt[String]('m', "method-name")
      .text("The name of the target procedure to mine. If undefined, matches all methods.")

  def showCallees(): OptionDef[Unit, T] =
    opt[Unit]('c', "show-callees")
      .text("Attempts to resolve and dump the methods called by the target method. Default is false.")

}
