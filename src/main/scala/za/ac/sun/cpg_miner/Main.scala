package za.ac.sun.cpg_miner

import better.files.File
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.joern.javasrc2cpg.JavaSrc2Cpg
import io.joern.x2cpg.{X2Cpg, X2CpgConfig}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import scala.util.{Failure, Success, Try, Using}

object Main {

  private val parser = new OptionParser[CpgMinerConfig]("cpg_miner") {
    help("help")
    head("Mines code snippets and sub-graphs from CPGs.")
    outDir()
      .required()
      .action((x, c) => c.copy(outputDir = File(x)))
    methodName()
      .optional()
      .action((x, c) => c.copy(methodName = Option(x)))
    showCallees()
      .action((_, c) => c.copy(showCallees = true))
    opt[Unit]("combine")
      .text("Combines all representations and methods into a single JSON CPG. Note this will exclude the code snippet.")
      .action((_, c) => c.copy(combine = true))
    cmd("from-graph")
      .children(
        inCpg()
          .required()
          .action((x, c) => c.copy(inputCpg = File(x)))
      )
    cmd("from-code")
      .children(
        inDir()
          .required()
          .action((x, c) =>
            generateCpg(File(x)) match
              case Failure(exception) => throw exception
              case Success(cpg)       => c.copy(inputCpg = cpg)
          )
      )
  }

  private def generateCpg(dir: File): Try[File] = Try {
    val inPath  = dir.pathAsString
    val outFile = dir / "cpg.bin"

    if (outFile.isRegularFile) {
      println(s"Existing $outFile found, overwriting...")
      outFile.delete(swallowIOExceptions = true)
    }

    def setInputAndOutput[T <: X2CpgConfig[T]](config: T): T = {
      config.withInputPath(inPath).withOutputPath(outFile.pathAsString)
    }

    def applyOverlaysAndClose(maybeCpg: Try[Cpg]): Unit = maybeCpg match {
      case Success(cpg) =>
        Using.resource(cpg) { x =>
          X2Cpg.applyDefaultOverlays(x)
          new OssDataFlow(new OssDataFlowOptions()).run(new LayerCreatorContext(x))
        }
      case Failure(exception) => throw exception
    }

    io.joern.console.cpgcreation.guessLanguage(inPath) match
      case Some(language) =>
        language match
          case Languages.JAVASRC =>
            val config = setInputAndOutput(JavaSrc2Cpg.DefaultConfig)
            applyOverlaysAndClose(JavaSrc2Cpg().createCpg(config))
            outFile
          case x =>
            throw new RuntimeException(s"This tool does not implement a language frontend for $x")
      case _ => throw new RuntimeException(s"Unable to guess programming language for ${dir.pathAsString}")
  }

  private def parseConfig(parserArgs: Array[String]): Either[String, CpgMinerConfig] = {
    parser.parse(parserArgs, CpgMinerConfig()) match {
      case Some(config) =>
        Right(config)
      case None =>
        Left("Could parse CLI args")
    }
  }

  def main(args: Array[String]): Unit = {
    parseConfig(args) match {
      case Left(_)       => System.exit(1)
      case Right(config) => CpgMethodMiner.mine(config)
    }
  }

}
