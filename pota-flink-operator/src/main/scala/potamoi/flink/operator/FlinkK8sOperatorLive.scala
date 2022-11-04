package potamoi.flink.operator

import com.coralogix.zio.k8s.client.NotFound
import com.coralogix.zio.k8s.client.kubernetes.Kubernetes
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.DeleteOptions
import org.apache.flink.client.deployment.application.ApplicationConfiguration
import potamoi.common.PathTool.{getFileName, isS3Path}
import potamoi.common.PrettyPrintable
import potamoi.common.ZIOExtension.{usingAttempt, ScopeZIOWrapper}
import potamoi.conf.PotaConf
import potamoi.flink.observer.FlinkK8sObserver
import potamoi.flink.observer.FlinkObrErr.flattenFlinkOprErr
import potamoi.flink.operator.FlinkConfigExtension.configurationToPF
import potamoi.flink.operator.FlinkK8sOperator.getClusterClientFactory
import potamoi.flink.operator.FlinkOprErr._
import potamoi.flink.share.FlinkExecMode.K8sSession
import potamoi.flink.share._
import potamoi.fs.{lfs, S3Operator}
import potamoi.k8s.stringToK8sNamespace
import sttp.client3._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.client3.ziojson._
import zio.ZIO.{attempt, attemptBlockingInterrupt, logInfo, scoped, succeed}
import zio._
import zio.json._

import java.io.File

/**
 * Default FlinkK8sOperator implementation.
 */
class FlinkK8sOperatorLive(potaConf: PotaConf, k8sClient: Kubernetes, s3Operator: S3Operator, flinkObserver: FlinkK8sObserver)
    extends FlinkK8sOperator {

  private val clDefResolver   = ClusterDefResolver
  private val podTplResolver  = PodTemplateResolver
  private val logConfResolver = LogConfigResolver

  /**
   * Local workplace directory for each Flink cluster.
   */
  private def clusterLocalWp(clusterId: String, namespace: String): UIO[String] =
    succeed(s"${potaConf.flink.localTmpDir}/${namespace}@${clusterId}")

  /**
   * Local Generated flink kubernetes pod-template file output path.
   */
  private def podTemplateFileOutputPath(clusterDef: FlinkClusterDef[_]): UIO[String] =
    clusterLocalWp(clusterDef.clusterId, clusterDef.namespace).map(wp => s"$wp/flink-podtemplate.yaml")

  /**
   * Local Generated flink kubernetes config file output path.
   */
  private def logConfFileOutputPath(clusterDef: FlinkClusterDef[_]): UIO[String] =
    clusterLocalWp(clusterDef.clusterId, clusterDef.namespace).map(wp => s"$wp/log-conf")

  /**
   * Deploy Flink Application cluster.
   */
  override def deployApplicationCluster(clusterDef: FlinkAppClusterDef): IO[FlinkOprErr, Unit] = {
    for {
      clusterDef <- clDefResolver.application.revise(clusterDef)
      // resolve flink pod template and log config
      podTemplateFilePath <- podTemplateFileOutputPath(clusterDef)
      logConfFilePath     <- logConfFileOutputPath(clusterDef)
      _                   <- podTplResolver.resolvePodTemplateAndDump(clusterDef, potaConf, podTemplateFilePath)
      _                   <- logConfResolver.ensureFlinkLogsConfigFiles(logConfFilePath, overwrite = true)
      // convert to effective flink configuration
      rawConfig <- clDefResolver.application.toFlinkRawConfig(clusterDef, potaConf).map { conf =>
        conf
          .append("kubernetes.pod-template-file.jobmanager", podTemplateFilePath)
          .append("kubernetes.pod-template-file.taskmanager", podTemplateFilePath)
          .append("$internal.deployment.config-dir", logConfFilePath)
      }
      _ <- logInfo(s"Start to deploy flink session cluster:\n${rawConfig.toMap(true).toPrettyString}".stripMargin)
      // deploy app cluster
      _ <- scoped {
        for {
          clusterClientFactory <- getClusterClientFactory(K8sSession)
          clusterSpecification <- attempt(clusterClientFactory.getClusterSpecification(rawConfig))
          appConfiguration     <- attempt(new ApplicationConfiguration(clusterDef.appArgs.toArray, clusterDef.appMain.orNull))
          k8sClusterDescriptor <- usingAttempt(clusterClientFactory.createClusterDescriptor(rawConfig))
          _                    <- attemptBlockingInterrupt(k8sClusterDescriptor.deployApplicationCluster(clusterSpecification, appConfiguration))
        } yield ()
      }.mapError(SubmitFlinkSessionClusterErr(clusterDef.fcid, _))
      _ <- logInfo(s"Deploy flink session cluster successfully.")
    } yield ()
  } @@ ZIOAspect.annotated(clusterDef.fcid.toAnno: _*)

  /**
   * Deploy Flink session cluster.
   */
  override def deploySessionCluster(clusterDef: FlinkSessClusterDef): IO[FlinkOprErr, Unit] = {
    for {
      clusterDef <- clDefResolver.session.revise(clusterDef)
      // resolve flink pod template and log config
      podTemplateFilePath <- podTemplateFileOutputPath(clusterDef)
      logConfFilePath     <- logConfFileOutputPath(clusterDef)
      _                   <- podTplResolver.resolvePodTemplateAndDump(clusterDef, potaConf, podTemplateFilePath)
      _                   <- logConfResolver.ensureFlinkLogsConfigFiles(logConfFilePath, overwrite = true)
      // convert to effective flink configuration
      rawConfig <- clDefResolver.session.toFlinkRawConfig(clusterDef, potaConf).map { conf =>
        conf
          .append("kubernetes.pod-template-file.jobmanager", podTemplateFilePath)
          .append("kubernetes.pod-template-file.taskmanager", podTemplateFilePath)
          .append("$internal.deployment.config-dir", logConfFilePath)
      }
      _ <- logInfo(s"Start to deploy flink application cluster:\n${rawConfig.toMap(true).toPrettyString}".stripMargin)
      // deploy cluster
      _ <- scoped {
        for {
          clusterClientFactory <- getClusterClientFactory(K8sSession)
          clusterSpecification <- attempt(clusterClientFactory.getClusterSpecification(rawConfig))
          k8sClusterDescriptor <- usingAttempt(clusterClientFactory.createClusterDescriptor(rawConfig))
          _                    <- attemptBlockingInterrupt(k8sClusterDescriptor.deploySessionCluster(clusterSpecification))
        } yield ()
      }.mapError(SubmitFlinkApplicationClusterErr(clusterDef.fcid, _))
      _ <- logInfo(s"Deploy flink application cluster successfully.")
    } yield ()
  } @@ ZIOAspect.annotated(clusterDef.fcid.toAnno: _*)

  /**
   * Submit job to Flink session cluster.
   */
  override def submitJobToSession(jobDef: FlinkSessJobDef): IO[FlinkOprErr, JobId] = {

    def uploadJar(filePath: String, restUrl: String)(implicit backend: SttpBackend[Task, Any]) =
      basicRequest
        .post(uri"$restUrl/jars/upload")
        .multipartBody(
          multipartFile("jarfile", new File(filePath))
            .fileName(getFileName(filePath))
            .contentType("application/java-archive")
        )
        .send(backend)
        .map(_.body)
        .flatMap(ZIO.fromEither(_))
        .flatMap(rsp => attempt(ujson.read(rsp)("filename").str.split("/").last))

    def runJar(jarId: String, restUrl: String)(implicit backend: SttpBackend[Task, Any]) =
      basicRequest
        .post(uri"$restUrl/jars/$jarId/run")
        .body(FlinkJobRunReq(jobDef))
        .send(backend)
        .map(_.body)
        .flatMap(ZIO.fromEither(_))
        .flatMap(rsp => attempt(ujson.read(rsp)("jobid").str))

    def deleteJar(jarId: String, restUrl: String)(implicit backend: SttpBackend[Task, Any]) =
      basicRequest
        .delete(uri"$restUrl/jars/$jarId")
        .send(backend)
        .ignore

    for {
      // get rest api url of session cluster
      restUrl <- flinkObserver.retrieveRestEndpoint(jobDef.clusterId -> jobDef.namespace).mapBoth(flattenFlinkOprErr, _.clusterIpRest)
      _       <- logInfo(s"Connect flink rest service: $restUrl")
      _       <- ZIO.fail(NotSupportJobJarPath(jobDef.jobJar)).unless(isS3Path(jobDef.jobJar))

      // download job jar
      _ <- logInfo(s"Downloading flink job jar from s3 storage: ${jobDef.jobJar}")
      jobJarPath <- s3Operator
        .download(jobDef.jobJar, s"${potaConf.flink.localTmpDir}/${jobDef.namespace}@${jobDef.clusterId}/${getFileName(jobDef.jobJar)}")
        .mapBoth(UnableToResolveS3Resource, _.getPath)

      // submit job
      _ <- logInfo(s"Start to submit job to flink cluster: \n${jobDef.toPrettyString}".stripMargin)
      jobId <- HttpClientZioBackend
        .scoped()
        .flatMap { implicit backend =>
          for {
            _     <- logInfo(s"Uploading flink job jar to flink cluster, path: $jobJarPath, flink-rest: $restUrl")
            jarId <- uploadJar(jobJarPath, restUrl)
            jobId <- runJar(jarId, restUrl)
            _     <- deleteJar(jarId, restUrl)
          } yield jobId
        }
        .mapError(err => RequestFlinkRestApiErr(err.toString))
        .endScoped()
      _ <- lfs.rm(jobJarPath).ignore.fork
      _ <- logInfo(s"Submit job to flink session cluster successfully, jobId: $jobId")
    } yield jobId
  } @@ ZIOAspect.annotated(Fcid(jobDef.clusterId, jobDef.namespace).toAnno: _*)

  private case class FlinkJobRunReq(
      @jsonField("entry-class") entryClass: Option[String],
      programArgs: Option[String],
      parallelism: Option[Int],
      savepointPath: Option[String],
      restoreMode: Option[String],
      allowNonRestoredState: Option[Boolean])

  private object FlinkJobRunReq {
    implicit def codec: JsonCodec[FlinkJobRunReq] = DeriveJsonCodec.gen[FlinkJobRunReq]

    def apply(jobDef: FlinkSessJobDef): FlinkJobRunReq = FlinkJobRunReq(
      entryClass = jobDef.appMain,
      programArgs = if (jobDef.appArgs.isEmpty) None else Some(jobDef.appArgs.mkString(" ")),
      parallelism = jobDef.parallelism,
      savepointPath = jobDef.savepointRestore.map(_.savepointPath),
      restoreMode = jobDef.savepointRestore.map(_.restoreMode.toString),
      allowNonRestoredState = jobDef.savepointRestore.map(_.allowNonRestoredState)
    )
  }

  /**
   * Cancel job in flink session cluster.
   */
  override def cancelSessionJob(fcid: Fcid, jobId: String, savepoint: FlinkJobSptConf): IO[FlinkOprErr, Option[SavepointTriggerId]] = {

    ???
  }

  /**
   * Cancel job in flink application cluster.
   */
  override def cancelApplicationJob(fcid: Fcid, savepoint: FlinkJobSptConf): IO[FlinkOprErr, Option[SavepointTriggerId]] = {
    ???
  }

  /**
   * Terminate the flink cluster and reclaim all associated k8s resources.
   */
  override def killCluster(fcid: Fcid): IO[FlinkOprErr, Unit] = {
    k8sClient.apps.v1.deployments
      .delete(name = fcid.clusterId, namespace = fcid.namespace, deleteOptions = DeleteOptions())
      .mapError {
        case NotFound => ClusterNotFound(fcid)
        case failure  => FlinkOprErr.RequestK8sApiErr(failure)
      }
      .unit <*
    logInfo(s"Delete flink cluster successfully.")
  } @@ ZIOAspect.annotated(fcid.toAnno: _*)

}
