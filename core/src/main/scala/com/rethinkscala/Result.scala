package com.rethinkscala

import com.fasterxml.jackson.annotation.{JsonTypeInfo, JsonIgnore, JsonProperty}
import com.rethinkscala.reflect.{GroupResultDeserializer, RethinkTypeResolverBuilder, Reflector}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonTypeResolver}
import scala.collection.generic.CanBuildFrom
import scala.collection.{IndexedSeqLike, SeqLike}
import scala.collection.mutable.{ArrayBuffer,Builder}


case class DocPath(root: Map[String, Any], paths: List[String]) {

  type M = Map[String,_]
  type IM = scala.collection.mutable.Map[String,_]

  def as[T] = find[T](root)

  private def find[T](value: Any, p: List[String] = paths): Option[T] = p.headOption.map {
    x => value match {

      case Some(v) => find[T](v, p)
      // TODO : Fix warnings
      case m: M => find[T](m.get(x), p.tail)
      // TODO : Fix warnings
      case m: IM => find[T](m.get(x), p.tail)
      case _ => None
    }
  }.getOrElse(value match {

    case Some(x) => Some(x.asInstanceOf[T])
    case x: Any => Some(x.asInstanceOf[T])
    case _ => None
  })

  def \(name: String) = DocPath(root, paths :+ name)
}

private[rethinkscala] class BasicDocument extends Document

trait Document  {


  @JsonIgnore
  private[rethinkscala] lazy val underlying: Map[String, Any] = Reflector.fromJson[Map[String, Any]](_raw)
  @JsonIgnore
  private[rethinkscala] var raw: String = _

  private def _raw = Option(raw).getOrElse({
    raw = Reflector.toJson(this)
    raw
  })


  def \(name: String) = DocPath(underlying, List(name))

  def apply(name: String*) = DocPath(underlying, name.toList)

  def toMap = underlying

  def toJson = _raw

  private[rethinkscala] def invokeBeforeInsert = beforeInsert


  protected def beforeInsert = {}

  protected def afterInsert = {}

  protected def afterInsert(id: String) = {}

  private[rethinkscala] def invokeAfterInsert = afterInsert

  private[rethinkscala] def invokeAfterInsert(id: String) = afterInsert(id)
}


class JsonDocument(json: String) {
  private[rethinkscala] lazy val underlying: Either[Map[String, Any], Seq[Any]] = if (raw.startsWith("{"))
    Left(Reflector.fromJson[Map[String, Any]](raw))
  else
    Right(Reflector.fromJson[Seq[Any]](raw))


  private[rethinkscala] var raw: String = json

  def \(name: String) = DocPath(toMap, List(name))

  def toMap = underlying fold(a => a, b => (b.zipWithIndex map {
    case (value: Any, index: Int) => (index.toString, value)

  }).toMap)

  def asRaw = raw

  def toInt = Integer.parseInt(raw, 10)


  def toList = underlying.fold(_.toList, _.toList)
}

trait KeyedDocument[K] extends Document {
  type Key = K
  val id: Key
}

trait GeneratesKeys {
  val generatedKeys: Seq[String]
}


case class ReturnValueExtractor[T](@JsonProperty("new_val") value: T)

trait ReturnValues {
  self: Document =>
  private var _returnedValue: Option[Any] = None

  def returnedValue[T](clazz: Class[T]): T = returnedValue(Manifest.classType(clazz)).getOrElse(null.asInstanceOf[T])

  def returnedValue[T](implicit mf: Manifest[T]): Option[T] = {

    if (_returnedValue.isEmpty) {
      try {
        _returnedValue = Some(Reflector.fromJson[ReturnValueExtractor[T]](raw).value)
      } catch {
        case e: Exception =>
      }

    }
    _returnedValue.asInstanceOf[Option[T]]
  }

}

case class DBResult(name: String, @JsonProperty("type") kind: String) extends Document


case class JoinResult[Left, Right](left: Left, right: Right) extends Document

class ZipResult[L, R] extends Document {


  private var _left: Option[L] = None
  private var _right: Option[R] = None

  def left(implicit mf: Manifest[L]): L = _left.getOrElse {
    val value = Reflector.fromJson[L](raw)
    _left = Some(value)
    value

  }

  def right(implicit mf: Manifest[R]): R = _right.getOrElse {
    val value = Reflector.fromJson[R](raw)
    _right = Some(value)
    value

  }
}

abstract class InfoResult(name: String, @JsonProperty("type") kind: String) extends Document


case class InsertResult(inserted: Int = 0, replaced: Int = 0, unchanged: Int = 0, errors: Int = 0, @JsonProperty("first_error") firstError: Option[String] = None,

                        @JsonProperty("generated_keys") generatedKeys: Seq[String],
                        deleted: Int = 0, skipped: Int = 0) extends Document with ReturnValues with GeneratesKeys {

  //private var _returnValues = ???


}

case class IndexStatusResult(index: String, ready: Boolean) extends Document

case class TableInfoResult(name: String, @JsonProperty("type") kind: String, db: DBResult) extends InfoResult(name, kind)


case class ChangeResult(replaced: Int, unchanged: Int, inserted: Int, deleted: Int, errors: Int, @JsonProperty("first_error") firstError: Option[String],
                        skipped: Int, @JsonProperty("generated_keys") generatedKeys: Seq[String]) extends Document
with ReturnValues
with GeneratesKeys

case class MatchResult(start: Int, end: Int, str: String, groups: Seq[MatchGroupResult]) extends Document

case class MatchGroupResult(start: Int, end: Int, str: String)

case class Profile(description: String, @JsonProperty("duration(ms)") duration: Double, @JsonProperty("sub_task") subTask: List[Profile], parallelTasks: Option[Profile])

case class QueryProfile[T](value: T, profile: Profile)


case class GroupMapReduceExtractor[T](reduction: T)

object GroupResult {

  def apply[Base](bases: GroupResultRecord[Base]*) = fromSeq(bases)

  def fromSeq[Base](buf: Seq[GroupResultRecord[Base]]): GroupResult[Base] = {
    var array = new ArrayBuffer[GroupResultRecord[Base]](buf.size)
    for (i <- 0 until buf.size) array += buf(i)
    new GroupResult[Base](array)
  }

  def newBuilder[Base]: Builder[GroupResultRecord[Base], GroupResult[Base]] = new ArrayBuffer mapResult fromSeq


  implicit def canBuildFrom[Base,From]: CanBuildFrom[GroupResult[_], GroupResultRecord[Base], GroupResult[Base]] =
    new CanBuildFrom[GroupResult[_], GroupResultRecord[Base], GroupResult[Base]] {
      def apply(): Builder[GroupResultRecord[Base], GroupResult[Base]] = newBuilder
      def apply(from: GroupResult[_]): Builder[GroupResultRecord[Base], GroupResult[Base]] = newBuilder
    }
}


@JsonDeserialize(using = classOf[GroupResultDeserializer])
@JsonTypeResolver(value = classOf[RethinkTypeResolverBuilder])
@JsonTypeInfo(use=JsonTypeInfo.Id.CUSTOM,include = JsonTypeInfo.As.WRAPPER_OBJECT)
class GroupResult[Base] protected (buffer: ArrayBuffer[GroupResultRecord[Base]])
  extends IndexedSeq[GroupResultRecord[Base]]
          with IndexedSeqLike[GroupResultRecord[Base], GroupResult[Base]] with Document with HasGroupRecords   {

  override protected[this] def newBuilder: Builder[GroupResultRecord[Base], GroupResult[Base]] =
    GroupResult.newBuilder

  def apply(idx: Int): GroupResultRecord[Base] = {
    if (idx < 0 || length <= idx) throw new IndexOutOfBoundsException
    buffer(idx)
  }

  def length = buffer.length

}

trait HasGroupRecords{


}


@JsonTypeResolver(value = classOf[RethinkTypeResolverBuilder])
case class GroupResultRecord[R](group: R, values: Seq[Map[String, Any]]) extends Document





case class GroupMapReduceResult(group: Int, reduction: Map[String, _]) extends Document {

  def as[T](implicit mf: Manifest[T]): T = Reflector.fromJson[GroupMapReduceExtractor[T]](raw).reduction

  def as[T](clazz: Class[T]): T = as(Manifest.classType(clazz))
}

