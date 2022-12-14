package potamoi.flink.observer

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import potamoi.cluster.{CborSerializable, ShardingProxy}
import potamoi.common.ActorExtension.ActorRefWrapper
import potamoi.config.{NodeRole, PotaConf}
import potamoi.flink.operator.flinkRest
import potamoi.flink.share.FlinkOprErr.ActorInteropErr
import potamoi.flink.share.model.{Fcid, FlinkJmMetrics}
import potamoi.logger.PotaLogger
import potamoi.syntax._
import potamoi.timex._
import potamoi.ziox._
import zio.CancelableFuture
import zio.ZIOAspect.annotated

/**
 * Akka cluster sharding proxy for [[JmMetricTracker]].
 */
private[observer] object JmMetricTrackerProxy extends ShardingProxy[Fcid, JmMetricTracker.Cmd] {
  val entityKey = EntityTypeKey[JmMetricTracker.Cmd]("flinkJmMetricsTracker")

  val marshallKey   = fcid => s"jmMt@${fcid.clusterId}@${fcid.namespace}"
  val unmarshallKey = _.split('@').contra(arr => arr(1) -> arr(2))

  def apply(potaConf: PotaConf, flinkEndpointQuery: RestEndpointQuery): Behavior[Cmd] = action(
    createBehavior = entityId => JmMetricTracker(entityId, potaConf, flinkEndpointQuery),
    stopMessage = JmMetricTracker.Stop,
    bindRole = NodeRole.FlinkOperator.toString
  )
}

/**
 * Flink taskmanager metrics tracker.
 */
private[observer] object JmMetricTracker {
  sealed trait Cmd                                                       extends CborSerializable
  final case object Start                                                extends Cmd
  final case object Stop                                                 extends Cmd
  sealed trait Query                                                     extends Cmd
  final case class GetJmMetrics(reply: ActorRef[Option[FlinkJmMetrics]]) extends Query
  private case class RefreshRecord(record: FlinkJmMetrics)               extends Cmd

  def apply(fcidStr: String, potaConf: PotaConf, flinkEndpointQuery: RestEndpointQuery): Behavior[Cmd] = {
    Behaviors.setup { implicit ctx =>
      val fcid = JmMetricTrackerProxy.unmarshallKey(fcidStr)
      ctx.log.info(s"Flink JmMetricTracker actor initialized, ${fcid.show}")
      new JmMetricTracker(fcid, potaConf: PotaConf, flinkEndpointQuery).action
    }
  }
}

private class JmMetricTracker(
    fcid: Fcid,
    potaConf: PotaConf,
    endpointQuery: RestEndpointQuery
  )(implicit ctx: ActorContext[JmMetricTracker.Cmd]) {
  import JmMetricTracker._

  private var proc: Option[CancelableFuture[Unit]] = None
  private var state: Option[FlinkJmMetrics]        = None

  def action: Behavior[Cmd] = Behaviors.receiveMessage {
    case Start =>
      if (proc.isEmpty) {
        proc = Some(pollingJmMetricsApi.provide(PotaLogger.layer(potaConf.log)).runToFuture)
        ctx.log.info(s"Flink JmMetricTracker actor started, ${fcid.show}")
      }
      Behaviors.same

    case Stop =>
      proc.map(_.cancel())
      ctx.log.info(s"Flink JmMetricTracker actor stopped, ${fcid.show}")
      Behaviors.stopped

    case RefreshRecord(records) =>
      state = records
      Behaviors.same

    case GetJmMetrics(reply) =>
      reply ! state
      Behaviors.same
  }

  private val pollingJmMetricsApi = loopTrigger(potaConf.flink.tracking.jmMetricsPolling) {
    for {
      restUrl   <- endpointQuery.get(fcid)
      jmMetrics <- flinkRest(restUrl.chooseUrl(potaConf.flink)).getJmMetrics(FlinkJmMetrics.metricsRawKeys).map(FlinkJmMetrics.fromRaw(fcid, _))
      _         <- ctx.self.tellZIO(RefreshRecord(jmMetrics)).mapError(ActorInteropErr)
    } yield ()
  } @@ annotated(fcid.toAnno :+ "akkaSource" -> ctx.self.path.toString: _*)

}
