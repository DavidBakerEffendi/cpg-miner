package za.ac.sun.cpg_miner

import better.files.File

object Main {

  private val parser = new OptionParser[CpgMinerConfig]("cpg_miner") {
    help("help")
    head("Mines code snippets and sub-graphs from CPGs.")
    inCpg()
      .required()
      .action((x, c) => c.copy(inputCpg = File(x)))
    outDir()
      .required()
      .action((x, c) => c.copy(outputDir = File(x)))
    methodName()
      .required()
      .action((x, c) => c.copy(methodName = x))
    showCallees()
      .action((_, c) => c.copy(showCallees = true))
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
