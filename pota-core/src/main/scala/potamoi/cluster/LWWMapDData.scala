package potamoi.cluster

import akka.actor.typed.SupervisorStrategy.restart
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.cluster.ddata.Replicator.{GetResponse, NotFound, UpdateResponse, WriteConsistency}
import akka.cluster.ddata.typed.scaladsl.{DistributedData, Replicator, ReplicatorMessageAdapter}
import akka.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import akka.util.Timeout
import potamoi.common.ActorExtension.{ActorRefWrapper, BehaviorWrapper}
import potamoi.common.ActorInteropException
import potamoi.config.DDataConf
import zio.IO

/**
 * Akka LWWMap type DData structure wrapped implementation.
 */
trait LWWMapDData[Key, Value] {

  sealed trait Cmd extends CborSerializable

  trait GetCmd                                                   extends Cmd
  final case class Get(key: Key, reply: ActorRef[Option[Value]]) extends GetCmd
  final case class Contains(key: Key, reply: ActorRef[Boolean])  extends GetCmd
  final case class ListKeys(reply: ActorRef[Set[Key]])           extends GetCmd
  final case class ListAll(reply: ActorRef[Map[Key, Value]])     extends GetCmd
  final case class Size(reply: ActorRef[Int])                    extends GetCmd

  trait UpdateCmd                                                                 extends Cmd
  final case class Put(key: Key, value: Value)                                    extends UpdateCmd
  final case class PutAll(kv: Map[Key, Value])                                    extends UpdateCmd
  final case class Remove(key: Key)                                               extends UpdateCmd
  final case class RemoveAll(keys: Set[Key])                                      extends UpdateCmd
  final case class RemoveBySelectKey(filter: Key => Boolean)                      extends UpdateCmd
  final case class Update(key: Key, updateValue: Value => Value)                  extends UpdateCmd
  final case class Upsert(key: Key, putValue: Value, updateValue: Value => Value) extends UpdateCmd

  sealed private trait InternalCmd                                                     extends Cmd
  final private case class InternalUpdate(rsp: UpdateResponse[LWWMap[Key, Value]])     extends InternalCmd
  final private case class InternalGet(rsp: GetResponse[LWWMap[Key, Value]], cmd: Cmd) extends InternalCmd

  lazy val cacheKey = LWWMapKey[Key, Value](cacheId)

  /**
   * LWWMap cache key.
   */
  def cacheId: String

  /*
   * LWWMap initial value.
   */
  def init: LWWMap[Key, Value] = LWWMap.empty

  /**
   * Start actor behavior.
   *
   * @param get             Additional extended [[GetCmd]] handling behavior.
   * @param update          Additional extended [[UpdateCmd]] handling behavior.
   * @param defaultNotFound Default response behavior of the extended [[GetCmd]] when the akka cluster
   *                        is not initialized with the corresponding DData.
   */
  // noinspection DuplicatedCode
  protected def start(
      conf: DDataConf,
      get: (GetCmd, LWWMap[Key, Value]) => Unit = (_, _) => (),
      defaultNotFound: GetCmd => Unit = _ => (),
      update: (UpdateCmd, LWWMap[Key, Value], SelfUniqueAddress) => LWWMap[Key, Value] = (_, m, _) => m): Behavior[Cmd] = {
    Behaviors.setup { implicit ctx =>
      implicit val node = DistributedData(ctx.system).selfUniqueAddress
      // ctx.log.info(s"Distributed data actor[$cacheId] started.")
      action(conf, get, defaultNotFound, update).onFailure[Exception](restart)
    }
  }

  /**
   * Receive message behavior.
   */
  // noinspection DuplicatedCode
  protected def action(
      conf: DDataConf,
      get: (GetCmd, LWWMap[Key, Value]) => Unit = (_, _) => (),
      defaultNotFound: GetCmd => Unit = _ => (),
      update: (UpdateCmd, LWWMap[Key, Value], SelfUniqueAddress) => LWWMap[Key, Value] = (_, m, node) => m
    )(implicit ctx: ActorContext[Cmd],
      node: SelfUniqueAddress): Behavior[Cmd] = {

    implicit val timeout = conf.askTimeout
    val writeLevel       = conf.writeLevel.asAkka
    val readLevel        = conf.readLevel.asAkka

    DistributedData.withReplicatorMessageAdapter[Cmd, LWWMap[Key, Value]] { implicit replicator =>
      val modifyShapePF = modifyShape(writeLevel)(_)
      Behaviors.receiveMessage {
        case cmd: GetCmd =>
          replicator.askGet(
            replyTo => Replicator.Get(cacheKey, readLevel, replyTo),
            rsp => InternalGet(rsp, cmd)
          )
          Behaviors.same

        case cmd: UpdateCmd =>
          cmd match {
            case Put(key, value) => modifyShapePF(cache => cache.put(node, key, value))
            case PutAll(kv)      => modifyShapePF(cache => kv.foldLeft(cache)((ac, c) => ac.put(node, c._1, c._2)))
            case Remove(key)     => modifyShapePF(cache => cache.remove(node, key))
            case RemoveAll(keys) => modifyShapePF(cache => keys.foldLeft(cache)((ac, c) => ac.remove(node, c)))
            case RemoveBySelectKey(filter) =>
              modifyShapePF { cache =>
                cache.entries.keys.filter(filter(_)).foldLeft(cache)((ac, c) => ac.remove(node, c))
              }
            case Update(key, modify) =>
              modifyShapePF { cache =>
                cache.get(key) match {
                  case None        => cache
                  case Some(value) => cache.put(node, key, modify(value))
                }
              }
            case Upsert(key, put, update) =>
              modifyShapePF { cache =>
                cache.get(key) match {
                  case None        => cache.put(node, key, put)
                  case Some(value) => cache.put(node, key, update(value))
                }
              }
            case c => modifyShapePF(cache => update(c, cache, node))
          }

        // get replica successfully
        case InternalGet(rsp @ Replicator.GetSuccess(cacheKey), cmd) =>
          val map = rsp.get(cacheKey)
          cmd match {
            case Get(key, reply)      => reply ! map.get(key)
            case Contains(key, reply) => reply ! map.contains(key)
            case ListKeys(reply)      => reply ! map.entries.keys.toSet
            case ListAll(reply)       => reply ! map.entries
            case Size(reply)          => reply ! map.size
            case c: GetCmd            => get(c, map)
          }
          Behaviors.same

        // update replica successfully
        case InternalUpdate(_ @Replicator.UpdateSuccess(_)) =>
          Behaviors.same

        // fail to get replica
        case InternalGet(rsp, cmd) =>
          rsp match {
            case NotFound(_, _) =>
              cmd match {
                case Get(_, reply)      => reply ! None
                case Contains(_, reply) => reply ! false
                case ListKeys(reply)    => reply ! Set.empty
                case ListAll(reply)     => reply ! Map.empty
                case Size(reply)        => reply ! 0
                case c: GetCmd          => defaultNotFound(c)
              }
            case _ => ctx.log.error(s"Get data replica failed: ${rsp.toString}")
          }
          Behaviors.same

        // fail to update replica
        case InternalUpdate(rsp) =>
          ctx.log.error(s"Update data replica failed: ${rsp.toString}")
          Behaviors.same
      }
    }
  }

  // noinspection DuplicatedCode
  private def modifyShape(
      writeLevel: WriteConsistency
    )(modify: LWWMap[Key, Value] => LWWMap[Key, Value]
    )(implicit replicator: ReplicatorMessageAdapter[Cmd, LWWMap[Key, Value]]): Behavior[Cmd] = {
    replicator.askUpdate(
      replyTo => Replicator.Update(cacheKey, init, writeLevel, replyTo)(modify(_)),
      rsp => InternalUpdate(rsp)
    )
    Behaviors.same
  }

  /**
   * ZIO interop.
   */
  type InteropIO[A] = IO[ActorInteropException, A]

  implicit class ZIOOperation(actor: ActorRef[Cmd])(implicit sc: Scheduler, askTimeout: Timeout) {
    def get(key: Key): InteropIO[Option[Value]]                                         = actor.askZIO(Get(key, _))
    def contains(key: Key): InteropIO[Boolean]                                          = actor.askZIO(Contains(key, _))
    def listKeys: InteropIO[Set[Key]]                                                   = actor.askZIO(ListKeys)
    def listAll: InteropIO[Map[Key, Value]]                                             = actor.askZIO(ListAll)
    def size: InteropIO[Int]                                                            = actor.askZIO(Size)
    def put(key: Key, value: Value): InteropIO[Unit]                                    = actor.tellZIO(Put(key, value))
    def putAll(kv: Map[Key, Value]): InteropIO[Unit]                                    = actor.tellZIO(PutAll(kv))
    def remove(key: Key): InteropIO[Unit]                                               = actor.tellZIO(Remove(key))
    def removeAll(keys: Set[Key]): InteropIO[Unit]                                      = actor.tellZIO(RemoveAll(keys))
    def removeBySelectKey(filter: Key => Boolean): InteropIO[Unit]                      = actor.tellZIO(RemoveBySelectKey(filter))
    def update(key: Key, updateValue: Value => Value): InteropIO[Unit]                  = actor.tellZIO(Update(key, updateValue))
    def upsert(key: Key, putValue: Value, updateValue: Value => Value): InteropIO[Unit] = actor.tellZIO(Upsert(key, putValue, updateValue))
  }

}
