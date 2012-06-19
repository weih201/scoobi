package com.nicta.scoobi
package impl
package plan
package comp

import org.kiama.attribution.Attributable
import core.{EnvDoFn, Emitter, BasicDoFn}
import io.DataSource

/**
 * GADT for distributed list computation graph.
 */
sealed trait DComp[+A, +Sh <: Shape] extends CompNode

/**
 * Base trait for "computation nodes" with no generic type information for easier rewriting
 */
trait CompNode extends Attributable {
  lazy val id = Id.get
}
/** The ParallelDo node type specifies the building of a DComp as a result of applying a function to
 * all elements of an existing DComp and concatenating the results. */
case class ParallelDo[A, B, E](in: DComp[A, Arr], env: DComp[E, Exp], dofn: EnvDoFn[A, B, E], groupBarrier: Boolean = false, fuseBarrier: Boolean = false) extends DComp[B, Arr] {
  override val toString = "ParallelDo ("+id+")" + (if (groupBarrier) "*" else "") + (if (fuseBarrier) "%" else "")
  def fuse[Z, G](p2: ParallelDo[B, Z, G]) = ParallelDo.fuse(this, p2)
}

/** The Flatten node type spcecifies the building of a DComp that contains all the elements from
 * one or more exsiting DLists of the same type. */
case class Flatten[A](ins: List[DComp[A, Arr]]) extends DComp[A, Arr] {
  override val toString = "Flatten ("+id+")"
}

/** The Combine node type specifies the building of a DComp as a result of applying an associative
 * function to the values of an existing key-values DComp. */
case class Combine[K, V](in: DComp[(K, Iterable[V]), Arr], f: (V, V) => V) extends DComp[(K, V), Arr] {

  override val toString = "Combine ("+id+")"
  /**
   * @return a ParallelDo node where the mapping uses the combine function to combine the Iterable[V] values
   */
  def toParallelDo = {
    val dofn = new BasicDoFn[(K, Iterable[V]), (K, V)] {
      def process(input: (K, Iterable[V]), emitter: Emitter[(K, V)]) {
        val (key, values) = input
        emitter.emit(key, values.reduce(f))
      }
    }
    // Return(()) is used as the Environment because there's no need for a specific value here
    ParallelDo(in, Return(()), dofn)
  }
}

/** The GroupByKey node type specifies the building of a DComp as a result of partitioning an exiting
 * key-value DComp by key. */
case class GroupByKey[K, V](in: DComp[(K, V), Arr]) extends DComp[(K, Iterable[V]), Arr] {
  override val toString = "GroupByKey ("+id+")"
}

/** The Load node type specifies the creation of a DComp from some source other than another DComp.
 * A DataSource object specifies how the loading is performed. */
case class Load[A](source: DataSource[_, _, A]) extends DComp[A, Arr] {
  override val toString = "Load ("+id+")"
}

/** The Return node type specifies the building of a Exp DComp from an "ordinary" value. */
case class Return[A](x: A) extends DComp[A, Exp] {
  override val toString = "Return ("+id+")"
}

/** The Materialize node type specifies the conversion of an Arr DComp to an Exp DComp. */
case class Materialize[A](in: DComp[A, Arr]) extends DComp[Iterable[A], Exp] {
  override val toString = "Materialize ("+id+")"
}

/** The Op node type specifies the building of Exp DComp by applying a function to the values
 * of two other Exp DComp nodes. */
case class Op[A, B, C](in1: DComp[A, Exp], in2: DComp[B, Exp], f: (A, B) => C) extends DComp[C, Exp] {
  override val toString = "Op ("+id+")"
}



object ParallelDo {
  def fuse[X, Y, Z, F, G](pd1: ParallelDo[X, Y, F], pd2: ParallelDo[Y, Z, G]): ParallelDo[X, Z, (F, G)] = {
    val ParallelDo(in1, env1, dofn1, gb1, _)   = pd1
    val ParallelDo(in2, env2, dofn2, gb2, fb2) = pd2
    new ParallelDo(in1, fuseEnv(env1, env2), fuseDoFn(dofn1, dofn2), gb1 || gb2, fb2)
  }

  /** Create a new ParallelDo function that is the fusion of two connected ParallelDo functions. */
  def fuseDoFn[X, Y, Z, F, G](f: EnvDoFn[X, Y, F], g: EnvDoFn[Y, Z, G]): EnvDoFn[X, Z, (F, G)] = new EnvDoFn[X, Z, (F, G)] {
    def setup(env: (F, G)) { f.setup(env._1); g.setup(env._2) }

    def process(env: (F, G), input: X, emitter: Emitter[Z]) {
      f.process(env._1, input, new Emitter[Y] { def emit(value: Y) { g.process(env._2, value, emitter) } } )
    }

    def cleanup(env: (F, G), emitter: Emitter[Z]) {
      f.cleanup(env._1, new Emitter[Y] { def emit(value: Y) { g.process(env._2, value, emitter) } })
      g.cleanup(env._2, emitter)
    }
  }

  /** Create a new environment by forming a tuple from two separate evironments.*/
  def fuseEnv[F, G](fExp: DComp[F, Exp], gExp: DComp[G, Exp]): DComp[(F, G), Exp] = Op(fExp, gExp, (f: F, g: G) => (f, g))

}

object Test {
  def test(n: CompNode) = n match {
    case Flatten(_) => true
  }
}
