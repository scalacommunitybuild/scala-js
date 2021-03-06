/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js tools             **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package org.scalajs.linker.backend.emitter

import java.net.URI

import org.scalajs.ir.ScalaJSVersions
import org.scalajs.io._

import org.scalajs.linker._
import org.scalajs.linker.standard._

import scala.collection.immutable.Seq
import scala.collection.mutable

private[emitter] object CoreJSLibs {

  private type Config = (Semantics, OutputMode, ModuleKind)

  private val cachedLibByConfig =
    mutable.HashMap.empty[Config, VirtualJSFile]

  private val ScalaJSEnvLines =
    ScalaJSEnvHolder.scalajsenv.split("\n|\r\n?")

  private val gitHubBaseURI =
    new URI("https://raw.githubusercontent.com/scala-js/scala-js/")

  def lib(semantics: Semantics, outputMode: OutputMode,
      moduleKind: ModuleKind): VirtualJSFile = {
    synchronized {
      cachedLibByConfig.getOrElseUpdate(
          (semantics, outputMode, moduleKind),
          makeLib(semantics, outputMode, moduleKind))
    }
  }

  private def makeLib(semantics: Semantics, outputMode: OutputMode,
      moduleKind: ModuleKind): VirtualJSFile = {
    new ScalaJSEnvVirtualJSFile(makeContent(semantics, outputMode, moduleKind))
  }

  private def makeContent(semantics: Semantics, outputMode: OutputMode,
      moduleKind: ModuleKind): String = {
    // This is a basic sort-of-C-style preprocessor

    def getOption(name: String): String = name match {
      case "asInstanceOfs" =>
        semantics.asInstanceOfs.toString()
      case "arrayIndexOutOfBounds" =>
        semantics.arrayIndexOutOfBounds.toString()
      case "moduleInit" =>
        semantics.moduleInit.toString()
      case "floats" =>
        if (semantics.strictFloats) "Strict"
        else "Loose"
      case "productionMode" =>
        semantics.productionMode.toString()
      case "outputMode" =>
        outputMode.toString()
      case "moduleKind" =>
        moduleKind.toString()
    }

    val originalLines = ScalaJSEnvLines

    var skipping = false
    var skipDepth = 0
    val lines = for (line <- originalLines) yield {
      val includeThisLine = if (skipping) {
        if (line == "//!else" && skipDepth == 1) {
          skipping = false
          skipDepth = 0
        } else if (line == "//!endif") {
          skipDepth -= 1
          if (skipDepth == 0)
            skipping = false
        } else if (line.startsWith("//!if ")) {
          skipDepth += 1
        }
        false
      } else {
        if (line.startsWith("//!")) {
          if (line.startsWith("//!if ")) {
            val Array(_, option, op, value) = line.split(" ")
            val optionValue = getOption(option)
            val success = op match {
              case "==" => optionValue == value
              case "!=" => optionValue != value
            }
            if (!success) {
              skipping = true
              skipDepth = 1
            }
          } else if (line == "//!else") {
            skipping = true
            skipDepth = 1
          } else if (line == "//!endif") {
            // nothing to do
          } else {
            throw new MatchError(line)
          }
          false
        } else {
          true
        }
      }
      if (includeThisLine) line
      else "" // blank line preserves line numbers in source maps
    }

    val content = lines.mkString("", "\n", "\n").replace(
        "{{LINKER_VERSION}}", ScalaJSVersions.current)

    outputMode match {
      case OutputMode.ECMAScript51Isolated =>
        content
          .replaceAll(raw"\b(let|const)\b", "var")

      case OutputMode.ECMAScript6 =>
        content
    }
  }

  private class ScalaJSEnvVirtualJSFile(override val content: String) extends VirtualJSFile {
    override def path: String = "scalajsenv.js"
    override def version: Option[String] = Some("")
    override def exists: Boolean = true

    override def toURI: URI = {
      if (!ScalaJSVersions.currentIsSnapshot)
        gitHubBaseURI.resolve(s"v${ScalaJSVersions.current}/tools/$path")
      else
        super.toURI
    }
  }

}
