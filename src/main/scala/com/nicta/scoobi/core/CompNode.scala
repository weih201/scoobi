package com.nicta.scoobi
package core

import org.kiama.attribution.Attributable
import scalaz.Equal

/**
 * Base trait for "computation nodes" with no generic type information for easier rewriting
 *
 * Each computation node has a unique id and equality of 2 nodes is based on this id.
 *
 * CompNodes are Attributable so that they can be used in attribute grammars
 */
trait CompNode extends Attributable {
  /** unique identifier for this computation node */
  def id: Int

  /** object defining how to serialise / deserialise  data for that node */
  def wf: WireReaderWriter

  override def equals(a: Any) = a match {
    case n: CompNode => n.id == id
    case other       => false
  }
  override def hashCode = id.hashCode
}

/**
 * Definition of the Equal instance for CompNodes
 */
object CompNode {
  implicit def compNodeEqual[T <: CompNode] = new Equal[T] {
    def equal(a1: T, a2: T) = a1.id == a2.id
  }
}

