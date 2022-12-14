package potamoi.flink.share.model

import potamoi.common.ComplexEnum
import potamoi.common.NumExtension.{DoubleWrapper, IntWrapper}
import potamoi.config.S3AccessStyle.PathStyle
import potamoi.config.S3Conf
import potamoi.flink.share.model.CheckpointStorageType.CheckpointStorageType
import potamoi.flink.share.model.FlinkRawConf.dryRawMapping
import potamoi.flink.share.model.SptRestoreMode.{Claim, SptRestoreMode}
import potamoi.flink.share.model.StateBackendType.StateBackendType
import potamoi.syntax.GenericPF
import zio.json.{DeriveJsonCodec, JsonCodec}

import scala.language.implicitConversions

/**
 * Type-safe flink major configuration entries.
 */
sealed trait FlinkRawConf {

  /**
   * Evaluation behavior of Flink's raw configuration.
   */
  def rawMapping: Vector[(String, Any)]

  def effectedRawMapping: Vector[(String, Any)] = dryRawMapping(rawMapping)
}

object FlinkRawConf {
  implicit val cpuConfCodec: JsonCodec[CpuConf]                = DeriveJsonCodec.gen[CpuConf]
  implicit val memConfCodec: JsonCodec[MemConf]                = DeriveJsonCodec.gen[MemConf]
  implicit val parConfCodec: JsonCodec[ParConf]                = DeriveJsonCodec.gen[ParConf]
  implicit val webUICodec: JsonCodec[WebUIConf]                = DeriveJsonCodec.gen[WebUIConf]
  implicit val restartStgCodec: JsonCodec[RestartStgConf]      = DeriveJsonCodec.gen[RestartStgConf]
  implicit val stateBackendCodec: JsonCodec[StateBackendConf]  = DeriveJsonCodec.gen[StateBackendConf]
  implicit val jmHaCodec: JsonCodec[JmHaConf]                  = DeriveJsonCodec.gen[JmHaConf]
  implicit val s3AccessConf: JsonCodec[S3AccessConf]           = DeriveJsonCodec.gen[S3AccessConf]
  implicit val sptRestoreConf: JsonCodec[SavepointRestoreConf] = DeriveJsonCodec.gen[SavepointRestoreConf]

  /**
   * Eliminate empty configuration items.
   */
  def dryRawMapping(mapping: Vector[(String, Any)]): Vector[(String, Any)] =
    mapping
      .filter { case (_, value) =>
        value match {
          case None                     => false
          case value: Iterable[_]       => value.nonEmpty
          case Some(value: Iterable[_]) => value.nonEmpty
          case _                        => true
        }
      }
      .map {
        case (key, Some(value)) => key -> value
        case (k, v)             => k   -> v
      }
}

/**
 * Flink k8s cpu configuration.
 */
case class CpuConf(jm: Double = 1.0, tm: Double = -1.0, jmFactor: Double = 1.0, tmFactor: Double = 1.0) extends FlinkRawConf {
  def rawMapping = Vector(
    "kubernetes.taskmanager.cpu"              -> jm.ensureDoubleOr(_ > 0, 1.0),
    "kubernetes.jobmanager.cpu.limit-factor"  -> jmFactor.ensureDoubleOr(_ > 0, 1.0),
    "kubernetes.taskmanager.cpu"              -> tm,
    "kubernetes.taskmanager.cpu.limit-factor" -> tmFactor.ensureDoubleOr(_ > 0, 1.0)
  )
}

/**
 * Flink parallelism configuration.
 */
case class ParConf(numOfSlot: Int = 1, parDefault: Int = 1) extends FlinkRawConf {
  def rawMapping = Vector(
    "taskmanager.numberOfTaskSlots" -> numOfSlot.ensureIntMin(1),
    "parallelism.default"           -> parDefault.ensureIntMin(1)
  )
}

/**
 * Flink memory configuration.
 */
case class MemConf(jmMB: Int = 1920, tmMB: Int = 1920) extends FlinkRawConf {
  def rawMapping = Vector(
    "jobmanager.memory.process.size"  -> jmMB.ensureIntOr(_ > 0, 1920).contra(_ + "m"),
    "taskmanager.memory.process.size" -> tmMB.ensureIntOr(_ > 0, 1920).contra(_ + "m")
  )
}

/**
 * Flink web ui service configuration.
 */
case class WebUIConf(enableSubmit: Boolean = true, enableCancel: Boolean = true) extends FlinkRawConf {
  def rawMapping = Vector(
    "web.submit.enable" -> enableSubmit,
    "web.cancel.enable" -> enableCancel
  )
}

/**
 * Flink task restart strategy.
 */
sealed trait RestartStgConf extends FlinkRawConf

case object NonRestartStg extends RestartStgConf {
  def rawMapping = Vector("restart-strategy" -> "none")
}

case class FixedDelayRestartStg(attempts: Int = 1, delaySec: Int = 1) extends RestartStgConf {
  def rawMapping = Vector(
    "restart-strategy"                      -> "fixed-delay",
    "restart-strategy.fixed-delay.attempts" -> attempts.ensureIntMin(1),
    "restart-strategy.fixed-delay.delay"    -> delaySec.ensureIntMin(1).contra(e => s"$e s")
  )
}

case class FailureRateRestartStg(delaySec: Int = 1, failureRateIntervalSec: Int = 60, maxFailuresPerInterval: Int = 1) extends RestartStgConf {
  def rawMapping = Vector(
    "restart-strategy"                                        -> "failure-rate",
    "restart-strategy.failure-rate.delay"                     -> failureRateIntervalSec.ensureIntMin(1).contra(e => s"$e s"),
    "restart-strategy.failure-rate.failure-rate-interval"     -> failureRateIntervalSec.ensureIntMin(1).contra(e => s"$e s"),
    "restart-strategy.failure-rate.max-failures-per-interval" -> maxFailuresPerInterval.ensureIntMin(1)
  )
}

/**
 * Flink state backend configuration.
 */
case class StateBackendConf(
    backendType: StateBackendType,
    checkpointStorage: CheckpointStorageType,
    checkpointDir: Option[String] = None,
    savepointDir: Option[String] = None,
    incremental: Boolean = false,
    localRecovery: Boolean = false,
    checkpointNumRetained: Int = 1)
    extends FlinkRawConf {

  def rawMapping = Vector(
    "state.backend"                  -> backendType.toString,
    "state.checkpoint-storage"       -> checkpointStorage.toString,
    "state.checkpoints.dir"          -> checkpointDir,
    "state.savepoints.dir"           -> savepointDir,
    "state.backend.incremental"      -> incremental,
    "state.backend.local-recovery"   -> localRecovery,
    "state.checkpoints.num-retained" -> checkpointNumRetained.ensureIntMin(1),
  )
}

object StateBackendType extends ComplexEnum {
  type StateBackendType = Value
  val Hashmap = Value("hashmap")
  val Rocksdb = Value("rocksdb")
}

object CheckpointStorageType extends ComplexEnum {
  type CheckpointStorageType = Value
  val Jobmanager = Value("jobmanager")
  val Filesystem = Value("filesystem")
}

/**
 * Flink Jobmanager HA configuration.
 */
case class JmHaConf(
    haImplClz: String = "org.apache.flink.kubernetes.highavailability.KubernetesHaServicesFactory",
    storageDir: String,
    clusterId: Option[String] = None)
    extends FlinkRawConf {

  def rawMapping = Vector(
    "high-availability"            -> haImplClz,
    "high-availability.storageDir" -> storageDir,
    "high-availability.cluster-id" -> clusterId
  )
}

/**
 * s3 storage access configuration.
 */
case class S3AccessConf(
    endpoint: String,
    accessKey: String,
    secretKey: String,
    pathStyleAccess: Option[Boolean] = None,
    sslEnabled: Option[Boolean] = None) {

  /**
   * Mapping to flink-s3-presto configuration.
   */
  def rawMappingS3p =
    Vector(
      "hive.s3.endpoint"          -> endpoint,
      "hive.s3.aws-access-key"    -> accessKey,
      "hive.s3.aws-secret-key"    -> secretKey,
      "hive.s3.path-style-access" -> pathStyleAccess,
      "hive.s3.ssl.enabled"       -> sslEnabled
    ).contra(dryRawMapping)

  /**
   * Mapping to flink-s3-hadoop configuration.
   */
  def rawMappingS3a =
    Vector(
      "fs.s3a.endpoint"               -> endpoint,
      "fs.s3a.access.key"             -> accessKey,
      "fs.s3a.secret.key"             -> secretKey,
      "fs.s3a.path.style.access"      -> pathStyleAccess,
      "fs.s3a.connection.ssl.enabled" -> sslEnabled
    ).contra(dryRawMapping)

}

object S3AccessConf {
  def apply(conf: S3Conf): S3AccessConf =
    S3AccessConf(conf.endpoint, conf.accessKey, conf.secretKey, Some(conf.accessStyle == PathStyle), Some(conf.sslEnabled))
}

object RestExportType extends ComplexEnum {
  type RestExportType = Value
  val ClusterIP         = Value("ClusterIP")
  val NodePort          = Value("NodePort")
  val LoadBalancer      = Value("LoadBalancer")
  val HeadlessClusterIP = Value("Headless_ClusterIP")
}

/**
 * Savepoint restore config.
 *
 * @see [[org.apache.flink.runtime.jobgraph.SavepointRestoreSettings]]
 */
case class SavepointRestoreConf(savepointPath: String, allowNonRestoredState: Boolean = false, restoreMode: SptRestoreMode = Claim)
    extends FlinkRawConf {
  def rawMapping = Vector(
    "execution.savepoint-restore-mode"           -> restoreMode.toString,
    "execution.savepoint.path"                   -> savepointPath,
    "execution.savepoint.ignore-unclaimed-state" -> allowNonRestoredState
  )
}

/**
 * @see [[org.apache.flink.runtime.jobgraph.RestoreMode]]
 */
object SptRestoreMode extends ComplexEnum {
  type SptRestoreMode = Value
  val Claim   = Value("CLAIM")
  val NoClaim = Value("NO_CLAIM")
  val Legacy  = Value("LEGACY")
}
