package potamoi.flink.observer

import com.softwaremill.quicklens.ModifyPimp
import potamoi.{LogsLevel, PotaLogger}
import potamoi.cluster.PotaActorSystem
import potamoi.conf.PotaConf
import potamoi.k8s.K8sClient
import potamoi.testkit.{STSpec, UnsafeEnv}

// TODO unsafe
class FlinkK8sObserverSpec extends STSpec {

  import FlinkK8sObserver._

  val conf = PotaConf.dev.modify(_.log.level).setTo(LogsLevel.DEBUG)

  val layers = {
    PotaConf.layer(conf) >+>
    PotaLogger.live ++
    PotaActorSystem.live ++ K8sClient.live >+>
    FlinkK8sObserver.live
  }

  "FlinkK8sObserver" should {

    "retrieve flink rest endpoint" taggedAs UnsafeEnv in {
      val ef = retrieveRestEndpoint("session-01" -> "fdev").debug

      val ef2 = retrieveRestEndpoint("session-01" -> "fdev").debug *>
        retrieveRestEndpoint("session-14" -> "fdev").debug
      ef2.provide(layers).run
    }

  }

}