package cromwell.core.simpleton

import wom.graph.GraphNodePort.OutputPort
import wom.values._

case class WomValueSimpleton(simpletonKey: String, simpletonValue: WomPrimitive)

/**
  * Encodes potentially complex `WomValue`s as `Iterable[WomValueSimpleton]`s using a protocol similar to that of
  * `MetadataValue`s: array elements are referenced by a square-brace enclosed index, map values are referenced by a
  * colon-prefixed key.  The `WomValueSimpleton` protocol differs slightly from the `MetadataValue` protocol because map
  * keys are not specified within Cromwell: WDL authors can choose any map keys allowed by WDL, possibly including square
  * brace or colon characters that this protocol uses as metacharacters.  So the `WomValueSimpleton` protocol escapes
  * any `[`, `]`, or `:` metacharacters in map keys with a leading backslash, which must then be unescaped whenever
  * `WomValueSimpleton`s are transformed back to `WomValue`s.
  */
object WomValueSimpleton {

  implicit class KeyMetacharacterEscaper(val key: String) extends AnyVal {
    // The escapes are necessary on the first arguments to `replaceAll` since they're treated like regular expressions
    // and square braces are character class delimiters.  Backslashes must be escaped in both parameters.
    // Ignore the red in some of the "raw" strings, IntelliJ and GitHub don't seem to understand them.
    def escapeMeta = key.replaceAll(raw"\[", raw"\\[").replaceAll(raw"\]", raw"\\]").replaceAll(":", raw"\\:")
    def unescapeMeta = key.replaceAll(raw"\\\[", "[").replaceAll(raw"\\\]", "]").replaceAll(raw"\\:", ":")
  }

  implicit class WomValueSimplifier(womValue: WomValue) {
    def simplify(name: String): Iterable[WomValueSimpleton] = womValue match {
      case prim: WomPrimitive => List(WomValueSimpleton(name, prim))
      case opt: WomOptionalValue => opt.value.map(_.simplify(name)).getOrElse(Seq.empty)
      case WomArray(_, arrayValue) => arrayValue.zipWithIndex flatMap { case (arrayItem, index) => arrayItem.simplify(s"$name[$index]") }
      case WomMap(_, mapValue) => mapValue flatMap { case (key, value) => value.simplify(s"$name:${key.valueString.escapeMeta}") }
      case WomPair(left, right) => left.simplify(s"$name:left") ++ right.simplify(s"$name:right")
      case wdlObject: WomObjectLike => wdlObject.value flatMap { case (key, value) => value.simplify(s"$name:${key.escapeMeta}") }
      case other => throw new Exception(s"Cannot simplify wdl value $other of type ${other.womType}")
    }
  }

  implicit class WomValuesSimplifier(wdlValues: Map[String, WomValue]) {
    def simplify: Iterable[WomValueSimpleton] = wdlValues flatMap { case (name, value) => value.simplify(name) }
  }

  implicit class WomValuesSimplifierPort(wdlValues: Map[OutputPort, WomValue]) {
    def simplify: Iterable[WomValueSimpleton] = wdlValues flatMap { case (port, value) => value.simplify(port.name) }
  }
}
