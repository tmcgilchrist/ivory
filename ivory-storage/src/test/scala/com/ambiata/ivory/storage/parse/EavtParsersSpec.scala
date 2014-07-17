package com.ambiata.ivory.storage.parse

import com.ambiata.ivory.core._
import com.ambiata.ivory.core.Arbitraries._

import org.joda.time.DateTimeZone
import org.scalacheck._, Arbitrary._
import org.specs2._, matcher._, specification._

import scalaz.{Value => _, _}, Scalaz._

class EavtParsersSpec extends Specification with ScalaCheck { def is = s2"""

Eavt Parse Formats
------------------

 Can parse date only                     $date
 Can parse legacy date-time format       $legacy
 Can parse standard date-time format     $standard
 Can parse with different time zones     $zones
 Must fail with bad EAVT string          $parsefail

"""
  def date = prop((fact: Fact) =>
    EavtParsers.fact(TestDictionary, fact.namespace, DateTimeZone.getDefault).run(List(
      fact.entity
    , fact.feature
    , fact.value.stringValue.getOrElse("?")
    , fact.date.hyphenated
    )) must_== Success(fact.withTime(Time(0))))

  def legacy = prop((fact: Fact) =>
    EavtParsers.fact(TestDictionary, fact.namespace, DateTimeZone.getDefault).run(List(
      fact.entity
    , fact.feature
    , fact.value.stringValue.getOrElse("?")
    , fact.date.hyphenated + " " + fact.time.hhmmss
    )) must_== Success(fact))

  def standard = prop((fz: SparseEntities) => {
    EavtParsers.fact(TestDictionary, fz.fact.namespace, fz.zone).run(List(
      fz.fact.entity
    , fz.fact.feature
    , fz.fact.value.stringValue.getOrElse("?")
    , fz.fact.datetime.iso8601(fz.zone)
    )) must_== Success(fz.fact)
  })

  def zones = prop((fz: SparseEntities) => {
    EavtParsers.fact(TestDictionary, fz.fact.namespace, fz.zone).run(List(
      fz.fact.entity
    , fz.fact.feature
    , fz.fact.value.stringValue.getOrElse("?")
    , fz.fact.date.hyphenated + " " + fz.fact.time.hhmmss
    )) must_== Success(fz.fact)
  })

  def parsefail = prop((bad: BadEavt) =>
    EavtParsers.fact(TestDictionary, bad.namespace, bad.timezone).run(bad.string.split("\\|").toList).toOption must beNone)

  /**
   * Arbitrary to create invalid EAVT strings such that the structure is correct, but the content is wrong in some way
   */
  case class BadEavt(string: String, namespace: String, timezone: DateTimeZone)
  implicit def BadEavtArbitrary: Arbitrary[BadEavt] = Arbitrary(for {
    e                   <- Gen.oneOf(testEntities(10000))
    (a, v, t, ns, m, z) <- Gen.oneOf(for {
      (f, m) <- Gen.oneOf(TestDictionary.meta.toList)
      a      <- arbitrary[String].retryUntil(s => !TestDictionary.meta.toList.exists(_._1.name == s))
      v      <- genValue(m)
      dtz    <- arbitrary[DateTimeWithZone]
    } yield (a, v, dtz.datetime, f.namespace, m, dtz.zone), for {
      (f, m) <- Gen.oneOf(TestDictionary.meta.toList).retryUntil(_._2.encoding != StringEncoding)
      a      <- Gen.const(f.name)
      bm     <- Gen.oneOf(TestDictionary.meta.toList).map(_._2).retryUntil(bm => !compatible(bm.encoding, m.encoding))
      v      <- genValue(bm).retryUntil(_.stringValue.map(s => !validString(s, m.encoding)).getOrElse(false))
      dtz    <- arbitrary[DateTimeWithZone]
    } yield (a, v, dtz.datetime, f.namespace, m, dtz.zone), for {
      (f, m) <- Gen.oneOf(TestDictionary.meta.toList)
      a      <- Gen.const(f.name)
      v      <- genValue(m)
      (t, z) <- arbitrary[BadDateTime].map(b => (b.datetime, b.zone))
    } yield (a, v, t, f.namespace, m, z))
  } yield BadEavt(s"$e|$a|${v.stringValue.getOrElse(m.tombstoneValue.head)}|${t.localIso8601}", ns, z))

  def compatible(from: Encoding, to: Encoding): Boolean =
    if(from == to) true else (from, to) match {
      case (_, StringEncoding)            => true
      case (IntEncoding, DoubleEncoding)  => true
      case (IntEncoding, LongEncoding)    => true
      case (LongEncoding, IntEncoding)    => true
      case (LongEncoding, DoubleEncoding) => true
      case _                              => false
    }

  def validString(s: String, e: Encoding): Boolean = e match {
    case StringEncoding  => true
    case IntEncoding     => s.parseInt.isSuccess
    case DoubleEncoding  => s.parseDouble.isSuccess
    case BooleanEncoding => s.parseBoolean.isSuccess
    case LongEncoding    => s.parseLong.isSuccess
    case _: StructEncoding => sys.error("Encoding of structs as strings not supported!")
  }

  def genValue(m: FeatureMeta): Gen[Value] =
    Gen.frequency(1 -> Gen.const(TombstoneValue()), 99 -> valueOf(m.encoding))
}
