package za.ac.sun.cpg_miner

import better.files.File
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import io.joern.dataflowengineoss.dotgenerator.DdgGenerator
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method, ControlStructure}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages, PropertyNames}
import io.shiftleft.semanticcpg.codedumper.CodeDumper
import io.shiftleft.semanticcpg.dotgenerator.DotSerializer.{Edge, Graph}
import io.shiftleft.semanticcpg.dotgenerator.{AstGenerator, CdgGenerator, CfgGenerator}
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import scala.util.Using

object CpgMethodMiner {

  private val logger = LoggerFactory.getLogger(getClass)

  def mine(config: CpgMinerConfig): Unit = {
    logger.info(s"Opening CPG at '${config.inputCpg.pathAsString}'")
    Using.resource(Cpg.withStorage(config.inputCpg.pathAsString)) { cpg =>
      cpg.metaData.language.headOption match
        case Some(language) => logger.info(s"CPG generated from a $language project")
        case None           => logger.warn("Could not determine the programming language of the CPG.")
      val matchingMethods = cpg.method
        .where(_.block.astChildren) // Avoid methods without implementations
        .nameExact(config.methodName)
        .l
      if (matchingMethods.isEmpty) {
        logger.warn(s"No methods with the name '${config.methodName}' found.")
      } else if (matchingMethods.size == 1) {
        logger.info(s"Found method with the name '${config.methodName}'.")
        matchingMethods.foreach { m =>
          val subdir = (config.outputDir / s"${m.name}").createDirectories()
          dumpMethodCode(cpg, m, subdir, config.showCallees)
          serializeGraph(m, subdir)
        }
      } else {
        logger.info(s"Found ${matchingMethods.size} method(s) with the name '${config.methodName}'.")
        matchingMethods.zipWithIndex.foreach { case (m, idx) =>
          val subdir = (config.outputDir / s"${m.name}_$idx").createDirectories()
          dumpMethodCode(cpg, m, subdir, config.showCallees)
          serializeGraph(m, subdir)
        }
      }
    }
  }

  private def dumpMethodCode(cpg: Cpg, method: Method, outDir: File, showCallees: Boolean): Unit = {
    val metaData      = cpg.metaData.headOption
    val language      = metaData.map(_.language)
    val rootPath      = metaData.map(_.root)
    val calledMethods = if (showCallees) method.call.callee(NoResolve).toList else List()
    val (externalMethods, internalMethods) =
      (method +: calledMethods)
        .filterNot(_.name.startsWith("<operator>"))
        .partition(_.isExternal)
    val methodString = internalMethods
      .where(_.block.astChildren)
      .map(m => CodeDumper.dump(m.location, language, rootPath, highlight = false, withArrow = false))
      .mkString(System.lineSeparator * 2) + externalMethods
      .map(m => stubToCode(m, language))
      .mkString(System.lineSeparator)

    val targetFile = outDir / s"code.${langToExt(language)}"
    targetFile.write(s"$methodString${System.lineSeparator}")(File.OpenOptions.append)
  }

  private def langToExt(lang: Option[String]): String =
    lang match
      case Some(x) if x == Languages.PYTHON || x == Languages.PYTHONSRC => "py"
      case Some(x) if x == Languages.C || x == Languages.NEWC           => "c"
      case Some(x) if x == Languages.JAVASCRIPT                         => "js"
      case Some(x) if x == Languages.JAVA || x == Languages.JAVASRC     => "java"
      case _                                                            => "txt"

  private def stubToCode(m: Method, language: Option[String]): String =
    language match
      case Some(x) if x == Languages.PYTHON || x == Languages.PYTHONSRC =>
        s"""
          |
          |def ${m.name}(${m.parameter.name.mkString(", ")}):
          |   pass
          |""".stripMargin
      // TODO: Add more languages
      case _ => // Default to C-style
        /** We usually wont know the types for external methods so we use "void" instead
          */
        def anyToVoid(t: String) = if (t == "ANY") "void" else t.split("\\.").last

        val typeName        = anyToVoid(m.methodReturn.typeFullName)
        val modifiers       = m.modifier.map(_.modifierType).mkString(" ")
        val parameters      = m.parameter.map(x => s"${anyToVoid(x.typeFullName)} ${x.name}").mkString(", ")
        val methodSignature = s"$modifiers$typeName ${m.name}($parameters)".strip()
        s"""
           |
           |$methodSignature {
           |  /* Unreachable code */
           |}
           |""".stripMargin

  case class Node(id: Long, label: String, name: String, code: String)

  case class JsonGraph(nodes: List[Node], edges: List[Edge])

  private def serializeGraph(method: Method, outDir: File): Unit = {
    outDir / s"ast.json" write graphToJson(AstGenerator().generate(method))
    outDir / s"cfg.json" write graphToJson(CfgGenerator().generate(method))
    val ddg = DdgGenerator().generate(method)
    outDir / s"ddg.json" write graphToJson(ddg)
    val cdg = CdgGenerator().generate(method)
    outDir / s"pdg.json" write graphToJson(ddg.++(cdg))
  }

  implicit val encodeEdge: Encoder[Edge] = Encoder.instance { case Edge(src, dst, _, label, edgeType) =>
    Json.obj("src" -> src.id().asJson, "dst" -> dst.id().asJson, "value" -> label.asJson, "label" -> edgeType.asJson)
  }

  implicit val encodeNode: Encoder[Node] = Encoder.instance { case Node(id, label, name, code) =>
    Json.obj("id" -> id.asJson, "label" -> label.asJson, "name" -> name.asJson, "code" -> code.asJson)
  }

  implicit val encodeGraph: Encoder[JsonGraph] = Encoder.instance { case JsonGraph(nodes, edges) =>
    Json.obj("nodes" -> nodes.asJson, "edges" -> edges.asJson)
  }

  private def graphToJson(graph: Graph): String = {
    val nodes = graph.vertices.map {
      case x: Call             => Node(x.id(), x.label, x.name, x.code)
      case x: Method           => Node(x.id(), x.label, x.name, x.signature)
      case x: ControlStructure => Node(x.id(), x.label, x.controlStructureType, x.code)
      case x =>
        Node(x.id(), x.label, x.property(PropertyNames.NAME, "<empty>"), x.property(PropertyNames.CODE, "<empty>"))
    }
    JsonGraph(nodes, graph.edges).asJson.spaces2
  }

}

case class CpgMinerConfig(
  inputCpg: File = File("."),
  outputDir: File = File("."),
  methodName: String = "",
  showCallees: Boolean = false
)
