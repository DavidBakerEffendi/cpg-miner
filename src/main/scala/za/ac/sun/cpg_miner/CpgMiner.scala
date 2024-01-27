package za.ac.sun.cpg_miner

import better.files.File
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import io.joern.dataflowengineoss.dotgenerator.DdgGenerator
import io.shiftleft.codepropertygraph.generated.nodes.{Call, ControlStructure, Method, StoredNode}
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, Languages, PropertyNames}
import io.joern.x2cpg.utils.ConcurrentTaskUtil
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

      val matchingMethods = config.methodName match {
        case Some(methodName) =>
          logger.info(s"Matching procedures with the name $methodName")
          cpg.method
            .where(
              _.and(
                _.block.astChildren, // Avoid methods without implementations
                _.not(_.isExternal)
              )
            )
            .nameExact(methodName)
            .l
        case None =>
          logger.info(s"Processing all procedures")
          cpg.method.isExternal(false).l
      }

      if (config.combine) mineWholeGraph(cpg, config)
      else matchAndMineMethods(cpg, matchingMethods, config)
    }
  }

  private def mineWholeGraph(cpg: Cpg, config: CpgMinerConfig): Unit = {
    val combinedGraph = {
      val astGen = new AstGenerator()
      val cfgGen = new CfgGenerator()
      val ddgGen = new DdgGenerator()
      val cdgGen = new CdgGenerator()
      // Break everything up into tasks
      val tasks = cpg.typeDecl.map { typeDecl => () =>
        {
          val ast = astGen.generate(typeDecl)
          val cfgAndDdg = typeDecl.method
            .map { method =>
              val cfg = cfgGen.generate(method)
              val ddg = ddgGen.generate(method)
              val cdg = cdgGen.generate(method)
              cfg ++ ddg ++ cdg
            }
            .reduceOption((a, b) => a ++ b)
            .getOrElse(Graph(List.empty, List.empty))
          ast ++ cfgAndDdg
        }
      }
      // Execute concurrently, using a fixed number of threads at any given time to keep memory under control
      ConcurrentTaskUtil
        .runUsingThreadPool(tasks)
        .flatMap(_.toOption)
        .reduceOption((a, b) => a ++ b)
        .getOrElse(Graph(List.empty, List.empty))
    }

    val interproceduralTypeEdges = (cpg.typeDecl.inE ++ cpg.typeDecl.outE ++ cpg.graph.edges(EdgeTypes.CALL))
      .map(e =>
        Edge(
          e.outNode().get().asInstanceOf[StoredNode],
          e.inNode().get().asInstanceOf[StoredNode],
          edgeType = e.label()
        )
      )
      .l
    val cpgGraph = combinedGraph ++ Graph(List.empty, interproceduralTypeEdges)

    config.outputDir / s"cpg.json" write graphToJson(cpgGraph)
  }

  private def matchAndMineMethods(cpg: Cpg, matchingMethods: List[Method], config: CpgMinerConfig): Unit = {
    if (matchingMethods.isEmpty) {
      logger.warn(s"No methods matched.")
    } else if (matchingMethods.size == 1) {
      logger.info(s"Found a matching method.")
      matchingMethods.foreach { m =>
        val subdir = (config.outputDir / s"${m.name}").createDirectories()
        dumpMethodCode(cpg, m, subdir, config.showCallees)
        serializeGraph(m, subdir)
      }
    } else {
      logger.info(s"Found ${matchingMethods.size} matching methods.")
      matchingMethods.zipWithIndex.foreach { case (m, idx) =>
        val subdir = (config.outputDir / s"${m.name}_$idx").createDirectories()
        dumpMethodCode(cpg, m, subdir, config.showCallees)
        serializeGraph(m, subdir)
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
  methodName: Option[String] = None,
  combine: Boolean = false,
  showCallees: Boolean = false
)
