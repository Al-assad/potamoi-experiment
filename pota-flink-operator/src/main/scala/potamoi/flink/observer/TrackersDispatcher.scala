package potamoi.flink.observer

import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy.restart
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import potamoi.common.ActorExtension.BehaviorWrapper
import potamoi.config.{FlinkConf, LogConf, NodeRole}
import potamoi.flink.share.model.Fcid
import potamoi.flink.share.repo.FlinkRepoHub

/**
 * Flink tracker actors dispatcher.
 */
object TrackersDispatcher {
  sealed trait Cmd
  final case class Track(fcid: Fcid)   extends Cmd
  final case class UnTrack(fcid: Fcid) extends Cmd

  val JobsTrackerEntityKey = EntityTypeKey[JobsTracker.Cmd]("flink-job-tracker")

  def apply(logConf: LogConf, flinkConf: FlinkConf, flinkObserver: FlinkK8sObserver, flinkRepo: FlinkRepoHub): Behavior[Cmd] =
    Behaviors.setup { implicit ctx =>
      val sharding = ClusterSharding(ctx.system)

      val jobTrackerRegion = sharding.init(
        Entity(JobsTrackerEntityKey)(entityCxt => JobsTracker(entityCxt.entityId, logConf, flinkConf, flinkObserver))
          .withSettings(ClusterShardingSettings(ctx.system).withNoPassivationStrategy)
          .withStopMessage(JobsTracker.Stop)
          .withRole(NodeRole.FlinkOperator.toString)
      )
      ctx.log.info("Flink TrackersDispatcher actor started.")

      Behaviors
        .receiveMessage[Cmd] {
          case Track(fcid) =>
            jobTrackerRegion ! ShardingEnvelope(marshallFcid(fcid), JobsTracker.Start)
            Behaviors.same
          case UnTrack(fcid) =>
            jobTrackerRegion ! ShardingEnvelope(marshallFcid(fcid), JobsTracker.Stop)
            Behaviors.same
        }
        .onFailure[Exception](restart)
    }
}
