package potamoi.fs

import potamoi.fs.OsToolSpec.{randomDir, randomFile, rmFile}
import potamoi.testkit.STSpec

import java.io.{File, FileWriter}
import scala.io.Source
import scala.reflect.io.Directory
import scala.util.{Random, Try, Using}

class LfsToolSpec extends STSpec {

  "OsTool" should {

    "rm file" in {
      randomFile { file =>
        lfs.rm(file.getPath).run
        file.exists() shouldBe false
      }
    }

    "rm directory" in {
      randomDir(0) { dir =>
        lfs.rm(dir.getPath).run
        dir.exists() shouldBe false
      }
      randomDir(5) { dir =>
        lfs.rm(dir.getPath).run
        dir.exists() shouldBe false
      }
    }

    "rm not exist file/directory" in {
      lfs.rm("test-233.txt").run
      lfs.rm("test-23/233").run
    }

    "write content to file" in {
      val file = new File(s"${System.currentTimeMillis}.txt")
      lfs.write(file.getPath, "hello world 你好").run
      Using(Source.fromFile(file))(_.mkString).get shouldBe "hello world 你好"
      rmFile(file)
    }

  }
}

object OsToolSpec {

  private val rand = new Random()

  def randomFile(f: File => Any): Unit = {
    val file = new File(genRandomFile())
    f(file)
    rmFile(file)
  }

  def randomDir(fileSize: Int)(f: File => Any): Unit = {
    val dir = new File(genRandomDirectory(fileSize))
    f(dir)
    rmDir(dir)
  }

  def genRandomFile(dir: String = ""): String = {
    val fileName = if (dir.isEmpty) s"test-${System.currentTimeMillis()}.txt" else s"dir/test-${System.currentTimeMillis()}.txt"
    Using(new FileWriter(fileName)) { io =>
      io.write((1 to 100).map(_ => rand.nextString(20)).mkString("\n"))
    }
    fileName
  }

  def genRandomDirectory(fileSize: Int) = {
    val dir = s"test-${System.currentTimeMillis()}"
    new File(dir).mkdir()
    (0 until fileSize).map(_ => genRandomFile(dir))
    dir
  }

  def rmFile(file: File) = Try(file.delete())

  def rmDir(file: File) = Try(new Directory(file).deleteRecursively())

}
