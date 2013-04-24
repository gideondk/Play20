package play.api.libs.iteratee

import scala.concurrent.ExecutionContext.Implicits.global
import Parsing._

import org.specs2.mutable._
import concurrent.duration.Duration
import concurrent.Await

object ParsingSpec extends Specification {

  "Parsing" should {

    "split case 1" in {

      val foldEC = TestExecutionContext()
      val data = Enumerator(List("xx", "kxckikixckikio", "cockik", "isdodskikisd", "ksdloii").map(_.getBytes): _*)
      val parsed = data |>>> Parsing.search("kiki".getBytes).transform(Iteratee.fold(List.empty[MatchInfo[Array[Byte]]]) { (s, c: MatchInfo[Array[Byte]]) => s :+ c }(foldEC))

      val result = Await.result(parsed, Duration.Inf).map {
        case Matched(kiki) => "Matched(" + new String(kiki) + ")"
        case Unmatched(data) => "Unmatched(" + new String(data) + ")"
      }.mkString(", ")

      result must equalTo (
        "Unmatched(xxkxc), Matched(kiki), Unmatched(xc), Matched(kiki), Unmatched(ococ), Matched(kiki), Unmatched(sdods), Matched(kiki), Unmatched(sdks), Unmatched(dloii)")
      foldEC.executionCount must equalTo(10)

    }

    "split case 1" in {

      val data = Enumerator(List("xx", "kxckikixcki", "k", "kicockik", "isdkikodskikisd", "ksdlokiikik", "i").map(_.getBytes): _*)
      val foldEC = TestExecutionContext()
      val parsed = data |>>> Parsing.search("kiki".getBytes).transform(Iteratee.fold(List.empty[MatchInfo[Array[Byte]]]) { (s, c: MatchInfo[Array[Byte]]) => s :+ c }(foldEC))

      val result = Await.result(parsed, Duration.Inf).map {
        case Matched(kiki) => "Matched(" + new String(kiki) + ")"
        case Unmatched(data) => "Unmatched(" + new String(data) + ")"
      }.mkString(", ")

      result must equalTo(
        "Unmatched(xxkxc), Matched(kiki), Unmatched(xckikkico), Unmatched(c), Matched(kiki), Unmatched(sdkikods), Matched(kiki), Unmatched(sdksdlok), Unmatched(ii), Matched(kiki), Unmatched()")
      foldEC.executionCount must equalTo(11)

    }

  }
}
