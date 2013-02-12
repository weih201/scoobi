package com.nicta.scoobi
package impl
package exec

import org.apache.commons.logging.LogFactory
import core._
import plan.comp._
import plan.mscr._
import monitor.Loggable._
import collection.Seqs._
import scalaz.{DList => _, _}
import concurrent.Promise
import Scalaz._
import org.apache.hadoop.mapreduce.Job
import control.Exceptions._

/**
 * Execution of Scoobi applications using Hadoop
 *
 * The overall process consists in:
 *
 *  - optimising the computation graph
 *  - defining "layers" of independent processing nodes
 *  - defining optimal Mscrs in each layer
 *  - executing each layer in sequence
 */
private[scoobi]
case class HadoopMode(sc: ScoobiConfiguration) extends Optimiser with MscrsDefinition with ShowNode with ExecutionMode {
  implicit lazy val logger = LogFactory.getLog("scoobi.HadoopMode")

  /** execute a DList, storing the results in DataSinks */
  def execute(list: DList[_]) { execute(list.getComp) }
  /** execute a DObject, reading the result from a BridgeStore */
  def execute(o: DObject[_]): Any = execute(o.getComp)
  /** execute a computation graph */
  def execute(node: CompNode): Any = node |> prepare |> executeNode

  /**
   * Prepare the execution of the graph by optimising it
   */
  private
  lazy val prepare: CompNode => CompNode = attr("prepare") { case node =>
    val toExecute = truncateAlreadyExecutedNodes(node.debug("Raw nodes", prettyGraph))
    checkSourceAndSinks(toExecute)(sc)
    val optimised = optimise(toExecute.debug("Active nodes", prettyGraph))
    optimised.debug("Optimised nodes", prettyGraph)
  }

  private def truncateAlreadyExecutedNodes(node: CompNode) =
    truncate(node) {
      case process: ProcessNode => process.bridgeStore.map(hasBeenFilled).getOrElse(false)
      case other                => false
    }

  /**
   * execute a computation node
   */
  private
  lazy val executeNode: CompNode => Any = {
    /** return the result of the last layer */
    def executeLayers(node: CompNode) {
      layers(node).debug("Executing layers", mkStrings).map(executeLayer)
    }
    // execute value nodes recursively, other nodes start a "layer" execution
    attr("executeNode") {
      case node @ Op1(in1, in2)    => node.execute(executeNode(in1), executeNode(in2))
      case node @ Return1(in)      => in
      case node @ Materialise1(in) => executeLayers(node); in.bridgeStore.map(read).getOrElse(Seq())
      case node                    => executeLayers(node)
    }
  }

  private lazy val executeLayer: Layer[T] => Unit =
    attr("executeLayer") { case layer =>
      ("executing layer "+layer.id).debug
      Execution(layer).execute
    }

  /**
   * Execution of a "layer" of Mscrs
   */
  private case class Execution(layer: Layer[T]) {

    def execute {
      ("Executing layer\n"+layer).debug
      runMscrs(mscrs(layer))

      layerSinks(layer).debug("Layer sinks: ").foreach(markSinkAsFilled)
      ("===== END OF LAYER "+layer.id+" ======").debug
    }

    /**
     * run mscrs concurrently if there are more than one.
     *
     * Only the execution part is done concurrently, not the configuration.
     * This is to make sure that there is not undesirable race condition during the setting up of variables
     */
    private def runMscrs(mscrs: Seq[Mscr]) {
      ("executing mscrs"+mscrs.mkString("\n", "\n", "\n")).debug

      val configured = mscrs.toList.map(configureMscr)
      val executed = if (sc.concurrentJobs) configured.map(executeMscr).sequence.get
                     else                   configured.map(_.execute)
      executed.map(reportMscr)
    }

    /** configure a Mscr */
    private def configureMscr = (mscr: Mscr) => {
      implicit val mscrConfiguration = sc.duplicate

      ("Loading input nodes for mscr "+mscr.id).debug
      mscr.inputNodes.foreach(load)

      ("Configuring mscr "+mscr.id).debug
      MapReduceJob(mscr, layer.id).configure
    }

    /** execute a Mscr */
    protected def executeMscr = (job: MapReduceJob) => {
      Promise(tryOr(job.execute)((e: Exception) => { e.printStackTrace; job }))
    }

    /** report the execution of a Mscr */
    protected def reportMscr = (job: MapReduceJob) => {
      job.report
      ("===== END OF MSCR "+job.mscr.id+" ======").debug
    }
  }

  /** @return the content of a Bridge as an Iterable */
  private def read(bs: Bridge): Any = {
    ("reading bridge "+bs.bridgeStoreId).debug
    bs.readAsIterable(sc)
  }

  /** make sure that all inputs environments are fully loaded */
  private def load(node: CompNode)(implicit sc: ScoobiConfiguration): Any = {
    node match {
      case rt @ Return1(in)      => pushEnv(rt, in)
      case op @ Op1(in1, in2)    => pushEnv(op, op.execute(load(in1), load(in2)))
      case mt @ Materialise1(in) => in.bridgeStore.map(bs => pushEnv(mt, read(bs))).getOrElse(Seq())
      case other                 => ()
    }
  }

  /**
   * once a node has been computed, if it defines an environment for another node push the value in the distributed cache
   * This method is synchronised because it can be called by several threads when Mscrs are executing in parallel to load
   * input nodes. However the graph attributes are not thread-safe and a "cyclic" evaluation might happen if several
   * thread are trying to evaluate the same attributes
   */
  private def pushEnv(node: CompNode, result: Any)(implicit sc: ScoobiConfiguration) = synchronized {
    usesAsEnvironment(node).map(_.pushEnv(result))
    result
  }

}

