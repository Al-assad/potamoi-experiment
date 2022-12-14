package potamoi.common

import scala.language.implicitConversions

object Syntax {

  /**
   * Contra control value to the function.
   */
  implicit class GenericPF[T](value: T) {
    @inline def contra[A](func: T => A): A = func(value)
  }

  /**
   * Auto convert value to Some
   */
  implicit def valueToSome[T](value: T): Option[T] = Some(value)

  /**
   * Trim String value safely.
   */
  def safeTrim(value: String): String = Option(value).map(_.trim).getOrElse("")

  /**
   * A more reader-friendly version of toString.
   */
  def toPrettyString(value: Any): String = pprint.apply(value, height = 2000).render

  implicit class PrettyPrintable(value: AnyRef) {
    def toPrettyStr: String = Syntax.toPrettyString(value)
  }

}
