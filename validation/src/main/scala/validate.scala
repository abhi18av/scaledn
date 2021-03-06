/*
 * Copyright (c) 2014 Pascal Voitot
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scaledn
package validate

import play.api.libs.functional._
import play.api.libs.functional.syntax._
import play.api.data.mapping._

import scaledn._


object Rules extends Rules


/** TRICKKKKKKK
  * scalac fails is if looking for recursive Rule[EDN, VT] so the trick is to go to SubRule
  */
trait SubRule[I, O] {
  def validate(data: I): VA[O]
}

object SubRule {
  def apply[I, O](f: I => VA[O]) = new SubRule[I, O]{
    def validate(data: I): VA[O] = f(data)
  }

  def fromMapping[I, O](f: Mapping[ValidationError, I, O]) =
    SubRule[I, O](f(_: I).fail.map(errs => Seq(play.api.data.mapping.Path -> errs)))

  def fromRule[I, O](rule: Rule[I, O]) = SubRule[I, O] { i => rule.validate(i) }

}

trait SubRule2[I, O] {
  def validate(data: I): VA[O]
}

object SubRule2 {
  def apply[I, O](f: I => VA[O]) = new SubRule2[I, O]{
    def validate(data: I): VA[O] = f(data)
  }

  def fromMapping[I, O](f: Mapping[ValidationError, I, O]) =
    SubRule2[I, O](f(_: I).fail.map(errs => Seq(play.api.data.mapping.Path -> errs)))

  def fromRule[I, O](rule: Rule[I, O]) = SubRule2[I, O] { i => rule.validate(i) }

}

trait Rules extends ValidationUtils with ShapelessRules {

  private def ednAs[T](f: PartialFunction[EDN, Validation[ValidationError, T]])(msg: String, args: Any*) =
    Rule.fromMapping[EDN, T](
      f.orElse {
        case j => Failure(Seq(ValidationError(msg, args: _*)))
      }
    )

  implicit def stringR = ednAs[String] {
    case s: String => Success(s)
  }("error.invalid", "String")

  implicit def booleanR = ednAs[Boolean] {
    case b: Boolean => Success(b)
  }("error.invalid", "Boolean")

  implicit def longR = ednAs[Long] {
    case l: Long => Success(l)
  }("error.number", "Long")

  implicit def bigIntR = ednAs[BigInt] {
    case b: BigInt => Success(b)
  }("error.number", "BigInt")

  implicit def intR = ednAs[Int] {
    case l: Long => Success(l.toInt)
    case b: BigInt if b.isValidInt => Success(b.toInt)
  }("error.number", "Int")

  implicit def shortR = ednAs[Short] {
    case l: Long => Success(l.toShort)
    case b: BigInt if b.isValidShort => Success(b.toShort)
  }("error.number", "Short")

  implicit def doubleR = ednAs[Double] {
    case d: Double => Success(d)
  }("error.number", "Double")

  implicit def BigDecimalR = ednAs[BigDecimal] {
    case b: BigDecimal => Success(b)
  }("error.number", "BigDecimal")

  // BigDecimal.isValidFloat is buggy, see [SI-6699]
  import java.{ lang => jl }
  private def isValidFloat(bd: BigDecimal) = {
    val d = bd.toFloat
    !d.isInfinity && bd.bigDecimal.compareTo(new java.math.BigDecimal(jl.Float.toString(d), bd.mc)) == 0
  }

  implicit def floatR = ednAs[Float] {
    case d: Double => Success(d.toFloat)
    case b: BigDecimal if(isValidFloat(b)) => Success(b.toFloat)
  }("error.number", "Float")

  implicit def symbolR = ednAs[EDNSymbol] {
    case s: EDNSymbol => Success(s)
  }("error.invalid", "Symbol")

  implicit def keywordR = ednAs[EDNKeyword] {
    case k: EDNKeyword => Success(k)
  }("error.invalid", "Keyword")

  implicit def listR = ednAs[List[EDN]] {
    case s: List[EDN] => Success(s)
  }("error.invalid", "List")

  implicit def setR = ednAs[Set[EDN]] {
    case s: Set[EDN @unchecked] => Success(s)
  }("error.invalid", "Set")

  implicit def vectorR = ednAs[Vector[EDN]] {
    case v: Vector[EDN] => Success(v)
  }("error.invalid", "Vector")

  implicit def mapR = ednAs[Map[EDN, EDN]] {
    case m: Map[EDN @unchecked, EDN @unchecked] => Success(m)
  }("error.invalid", "Map")

  implicit def mapKVR[K, V](implicit rk: RuleLike[EDN, K], rv: RuleLike[EDN, V]): Rule[EDN, Map[K, V]] = {
    mapR.compose(Path)(
      Rule { kvs =>
        val rkr = Rule.toRule(rk)
        val rvr = Rule.toRule(rv)
        val validations = kvs.toSeq.map { case kv =>
          val vk = rkr.repath((Path \ (kv._1.toString + "-key")) ++ _).validate(kv._1)
          val vv = rvr.repath((Path \ (kv._1.toString + "-value")) ++ _).validate(kv._2)
          tupled2(vk, vv)
        }
        Validation.sequence(validations).map(_.toMap)
      }
    )
  }

  implicit def pickInEdn[II <: EDN, O](p: Path)(implicit r: RuleLike[EDN, O]): Rule[II, O] = {

    def search(path: Path, edn: EDN): Option[EDN] = path.path match {
      case KeyPathNode(k) :: t =>
        edn match {
          case m: Map[EDN @unchecked, EDN @unchecked] =>
            m.find(_._1.toString == k).flatMap(kv => search(Path(t), kv._2))
          case _ => None
        }
      case IdxPathNode(i) :: t =>
        edn match {
          case l: List[EDN] => l.lift(i).flatMap(j => search(Path(t), j))
          case _ => None
        }
      case Nil => Some(edn)
    }

    Rule[II, EDN] { edn =>
      search(p, edn) match {
        case None => Failure(Seq(Path -> Seq(ValidationError("error.required"))))
        case Some(edn) => Success(edn)
      }
    }.compose(r)
  }

  def seqR[I]: Rule[EDN, Seq[EDN]] = listR.fmap(_.toSeq) orElse vectorR.fmap(_.toSeq)

  def _seqR[I, O](implicit r: RuleLike[I, O]): Rule[Seq[I], Seq[O]] =
    Rule { is: Seq[I] =>
        val rr = Rule.toRule(r)
        val withI = is.zipWithIndex.map {
          case (v, i) =>
            rr.repath((Path \ i) ++ _).validate(v)
        }
        traverse(withI)
    }

  implicit def pickSeq[O](implicit r: RuleLike[EDN, O]): Rule[EDN, Seq[O]] = seqR compose _seqR[EDN, O]

  def _listR[I, O](implicit r: RuleLike[I, O]): Rule[List[I], List[O]] =
    Rule { is: List[I] =>
        val rr = Rule.toRule(r)
        val withI = is.zipWithIndex.map {
          case (v, i) =>
            rr.repath((Path \ i) ++ _).validate(v)
        }
        traverse(withI)
    }

  implicit def pickList[O](implicit r: RuleLike[EDN, O]): Rule[EDN, List[O]] = listR compose _listR[EDN, O]

  def _setR[I, O](implicit r: RuleLike[I, O]): Rule[Set[I], Set[O]] =
    Rule { is: Set[I] =>
        val rr = Rule.toRule(r)
        val withI = is.zipWithIndex.map {
          case (v, i) =>
            rr.repath((Path \ v.toString) ++ _).validate(v)
        }
        traverse(withI)
    }

  implicit def pickSet[O](implicit r: RuleLike[EDN, O]): Rule[EDN, Set[O]] = setR compose _setR[EDN, O]

  def _vectorR[I, O](implicit r: RuleLike[I, O]): Rule[Vector[I], Vector[O]] =
    Rule { is: Vector[I] =>
      val rr = Rule.toRule(r)
      val withI = is.zipWithIndex.map {
        case (v, i) =>
          rr.repath((Path \ i) ++ _).validate(v)
      }
      traverse(withI)
    }

  implicit def pickVector[O](implicit r: RuleLike[EDN, O]): Rule[EDN, Vector[O]] = vectorR compose _vectorR[EDN, O]

  implicit val nilR = ednAs[EDNNil.type] {
    case EDNNil => Success(EDNNil)
  }("error.invalid", "nil")

  implicit def ooo[O](p: Path)(implicit pick: Path => RuleLike[EDN, EDN], coerce: RuleLike[EDN, O]): Rule[EDN, Option[O]] =
    optionR(Rule.zero[O])(pick, coerce)(p)

  def optionR[J, O](r: => RuleLike[J, O], noneValues: RuleLike[EDN, EDN]*)(
    implicit pick: Path => RuleLike[EDN, EDN], coerce: RuleLike[EDN, J]
  ): Path => Rule[EDN, Option[O]] =
    super.opt[J, O](r, (nilR.fmap(n => n: EDN) +: noneValues): _*)

}


trait ShapelessRules extends ValidationUtils with LowerRules {
  import shapeless.{HList, ::, HNil, Unpack2, Witness, LabelledGeneric, Generic, IsTuple}
  import shapeless.labelled.FieldType
  import shapeless.ops.hlist.IsHCons

  // import shapelessext._


  def ap2[HH, HT <: HList](head: VA[HH], tail: VA[HT])(implicit applicative: Applicative[VA]): VA[HH::HT] =
    applicative.apply(
      applicative.map(
        head,
        (h: HH) => (t: HT) => h :: t
      ),
      tail
    )


  implicit val hnilR: Rule[EDN, HNil] = Rule.fromMapping[EDN, HNil] {
    case l: List[EDN] if l.isEmpty => Success(HNil)
    case s: Set[EDN @ unchecked] if s.isEmpty => Success(HNil)
    case v: Vector[EDN] if v.isEmpty => Success(HNil)
    case m: Map[EDN @unchecked, EDN @unchecked] /* map doesn't use head/tail if m.isEmpty*/ => Success(HNil)
    case a => Failure(Seq(ValidationError("error.invalid", "HNil")))
  }


  /** TRICKKKKKKK
    * scalac fails is if looking for recursive Rule[EDN, VT] so the trick is to go to SubRule
    */
  implicit def hlistR[HH, HT <: HList, K, V](
    implicit
      hr: Rule[EDN, HH],
      ht: SubRule[EDN, HT],
      applicative: Applicative[VA]
  ): Rule[EDN, HH :: HT] = Rule[EDN, HH :: HT]{
    case head :: tail =>
      ap2(hr.validate(head), ht.validate(tail))
    case l: List[EDN] if !l.isEmpty =>
      ap2(hr.validate(l.head), ht.validate(l.tail))
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(hr.validate(v.head), ht.validate(v.tail))
    case m: Map[EDN @unchecked, EDN @unchecked] if !m.isEmpty =>
      ap2(hr.validate(m), ht.validate(m))
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "HList Rule (only supports non empty HList, List & Vector and any Map)"))))
  }

  /** TRICKKKKKKK
    * scalac fails is if looking for recursive Rule[EDN, VT] so the trick is to go to SubRule
    */
  implicit def tupleR[P, VS <: HList, VH, VT <: HList](
    implicit 
      cc: IsTuple[P],
      genValues: Generic.Aux[P, VS],
      c2: IsHCons.Aux[VS, VH, VT],      
      vhr: Rule[EDN, VH],
      vtr: SubRule[EDN, VT]
  ): Rule[EDN, P] = Rule[EDN, P]{ edn => edn match {
    case head :: tail =>
      ap2(vhr.validate(head), vtr.validate(tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case l: List[EDN] if !l.isEmpty =>
      ap2(vhr.validate(l.head), vtr.validate(l.tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(vhr.validate(v.head), vtr.validate(v.tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "Tuple Rule (only supports non empty HList, List, Vector)"))))
  }}
  

  implicit def fieldTypeR[K <: Symbol, V](
    implicit witness: Witness.Aux[K], wv: RuleLike[EDN, V]
  ): Rule[EDN, FieldType[K, V]] = {
    import shapeless.labelled._
    import play.api.data.mapping.Path
    (play.api.data.mapping.Path \ witness.value.name).read[EDN, V].fmap { v => field[K](v) }
  }


  implicit val hnilSR: SubRule[EDN, HNil] = SubRule.fromMapping[EDN, HNil] {
    case l: List[EDN] if l.isEmpty => Success(HNil)
    case s: Set[EDN @ unchecked] if s.isEmpty => Success(HNil)
    case v: Vector[EDN] if v.isEmpty => Success(HNil)
    case m: Map[EDN @unchecked, EDN @unchecked] /* map doesn't use head/tail if m.isEmpty*/ => Success(HNil)
    case a => Failure(Seq(ValidationError("error.invalid", "HNil")))
  }

  // SubRule higher level for Hlist of FieldTypes
  implicit def hlistSRF[HH, HT <: HList, K, V](
    implicit
      un: Unpack2[HH, FieldType, K, V],
      hr: Rule[EDN, FieldType[K, V]],
      ht: SubRule[EDN, HT],
      applicative: Applicative[VA]
  ): SubRule[EDN, HH :: HT] = SubRule[EDN, HH :: HT]{
    case scala.::(head, tail) =>
      ap2(hr.validate(head).map(l => l.asInstanceOf[HH]), ht.validate(tail))
    case l: List[EDN] if !l.isEmpty =>
      ap2(hr.validate(l.head).map(l => l.asInstanceOf[HH]), ht.validate(l.tail))
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(hr.validate(v.head).map(l => l.asInstanceOf[HH]), ht.validate(v.tail))
    case m: Map[EDN @unchecked, EDN @unchecked] if !m.isEmpty =>
      ap2(hr.validate(m).map(l => l.asInstanceOf[HH]), ht.validate(m))
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "HList Rule (only supports non empty HList, List & Vector and any Map)"))))
  }

}



trait LowerRules extends play.api.data.mapping.DefaultRules[EDN] {
  import shapeless.{HList, ::, HNil, Unpack2, Witness, LabelledGeneric, Generic, HasProductGeneric, <:!<}
  import shapeless.labelled.FieldType
  import shapeless.ops.hlist.IsHCons


  /** TRICKKKKKKK
    * scalac fails is if looking for recursive Rule[EDN, VT] so the trick is to go to SubRule
    */
  implicit def caseClassR[P, HL <: HList, HH, HT <: HList, K, V, VS <: HList, VH, VT <: HList](
    implicit 
      cc: HasProductGeneric[P],
      not: P <:!< EDNValue,
      genFields: LabelledGeneric.Aux[P, HL],
      c1: IsHCons.Aux[HL, HH, HT],      
      un1: Unpack2[HH, FieldType, K, V],
      hhr: Rule[EDN, FieldType[K, V]],
      htr: SubRule[EDN, HT],
      genValues: Generic.Aux[P, VS],
      c2: IsHCons.Aux[VS, VH, VT],      
      vhr: Rule[EDN, VH],
      vtr: SubRule2[EDN, VT]
  ): Rule[EDN, P] = Rule[EDN, P]{ edn => edn match {
    case head :: tail =>
      ap2(vhr.validate(head), vtr.validate(tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case l: List[EDN] if !l.isEmpty =>
      ap2(vhr.validate(l.head), vtr.validate(l.tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(vhr.validate(v.head), vtr.validate(v.tail)).map{ l => genValues.from(l.asInstanceOf[VS]) }
    case m: Map[EDN @unchecked, EDN @unchecked] if !m.isEmpty =>
      ap2(hhr.validate(m), htr.validate(m)).map{ l => genFields.from(l.asInstanceOf[HL]) }
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "CaseClass Rule (only supports non empty HList, List, Vector & Map)"))))
  }}
  
  implicit val hnilSR2: SubRule2[EDN, HNil] = SubRule2.fromMapping[EDN, HNil] {
    case l: List[EDN] if l.isEmpty => Success(HNil)
    case s: Set[EDN @ unchecked] if s.isEmpty => Success(HNil)
    case v: Vector[EDN] if v.isEmpty => Success(HNil)
    case m: Map[EDN @unchecked, EDN @unchecked] /* map doesn't use head/tail if m.isEmpty*/ => Success(HNil)
    case a => Failure(Seq(ValidationError("error.invalid", "HNil")))
  }

  implicit def hlistSR2[HH, HT <: HList, K, V](
    implicit
      hr: Rule[EDN, HH],
      ht: SubRule2[EDN, HT],
      applicative: Applicative[VA]
  ): SubRule2[EDN, HH :: HT] = SubRule2[EDN, HH :: HT]{
    case head :: tail =>
      ap2(hr.validate(head), ht.validate(tail))
    case l: List[EDN] if !l.isEmpty =>
      ap2(hr.validate(l.head), ht.validate(l.tail))
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(hr.validate(v.head), ht.validate(v.tail))
    case m: Map[EDN @unchecked, EDN @unchecked] if !m.isEmpty =>
      ap2(hr.validate(m), ht.validate(m))
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "HList Rule (only supports non empty HList, List & Vector and any Map)"))))
  }

  // SubRule lower level for Hlist
  implicit def hlistSR[HH, HT <: HList, K, V](
    implicit
      hr: Rule[EDN, HH],
      ht: SubRule2[EDN, HT],
      applicative: Applicative[VA]
  ): SubRule[EDN, HH :: HT] = SubRule[EDN, HH :: HT]{
    case head :: tail =>
      ap2(hr.validate(head), ht.validate(tail))
    case l: List[EDN] if !l.isEmpty =>
      ap2(hr.validate(l.head), ht.validate(l.tail))
    case v: Vector[EDN] if !v.isEmpty =>
      ap2(hr.validate(v.head), ht.validate(v.tail))
    case m: Map[EDN @unchecked, EDN @unchecked] if !m.isEmpty =>
      ap2(hr.validate(m), ht.validate(m))
    case a =>
      Failure(Seq(play.api.data.mapping.Path -> Seq(ValidationError("error.invalid", "HList Rule (only supports non empty HList, List & Vector and any Map)"))))
  }

}

trait ValidationUtils {
  import scala.collection.generic.CanBuildFrom

  def traverse[E, A, B, M[X] <: TraversableOnce[X], N[X] <: TraversableOnce[X]](vs: M[Validation[E, A]])(
    implicit cbf: CanBuildFrom[M[A], A, N[A]]
  ): Validation[E, N[A]] = {
    vs.foldLeft[Validation[E, N[A]]](Success(cbf().result)) {
      case (Success(as),  Success(a))   => Success((cbf() ++= as += a).result)
      case (Success(_),   Failure(e))   => Failure(e)
      case (Failure(e),   Success(_))   => Failure(e)
      case (Failure(e1),  Failure(e2))  => Failure(e1 ++ e2)
    }
  }

  def tupled2[E, A, B](vs: (Validation[E, A], Validation[E, B])): Validation[E, (A, B)] = {
    vs match {
      case (Success(a),  Success(b))   => Success((a, b))
      case (Success(_),   Failure(e))   => Failure(e)
      case (Failure(e),   Success(_))   => Failure(e)
      case (Failure(e1),  Failure(e2))  => Failure(e1 ++ e2)
    }
  }
}