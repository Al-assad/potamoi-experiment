package potamoi.fs

import potamoi.common.MimeType
import potamoi.testkit.{PotaDev, STSpec}
import zio.ZLayer


// TODO unsafe
class S3OperatorSpec extends STSpec {

  val layer = ZLayer.succeed(PotaDev.rawConf.s3) >>> S3Operator.clive

  "s3 operator" should {
    "download" in {
      S3Operator
        .download("s3://flink-dev/flink-connector-jdbc-1.15.2.jar", "spec-test/flink-connector-jdbc-1.15.2.jar")
        .provide(layer)
        .debug
        .runSpec
    }
    "upload" in {
      S3Operator
        .upload("spec-test/flink-connector-jdbc-1.15.2.jar", "s3://flink-dev/flink-connector-jdbc-1.15.2@re.jar", MimeType.jar)
        .provide(layer)
        .debug
        .runSpec
    }
    "remove" in {
      S3Operator
        .remove("s3://flink-dev/flink-connector-jdbc-1.15.2@re.jar")
        .provide(layer)
        .debug
        .runSpec
    }
    "exists" in {
      S3Operator
        .exists("s3://flink-dev/flink-connector-jdbc-1.15.2@re.jar")
        .provide(layer)
        .debug
        .runSpec
      S3Operator
        .exists("s3://flink-dev/flink-connector-jdbc-1.15.2.jar")
        .provide(layer)
        .debug
        .runSpec
    }
  }

  override protected def afterAll(): Unit = lfs.rm("spec-test")
}
