Scala Rethinkdb Driver
=========

This is a WIP but should be valid for 1.6

FEATURES
 - Full Type Safety (still a work in progress, will use macros to support case class type safety, right now all queries should be typed checked against the rules of RQL, )
 - Mapping to and from case classes , this allows you to fetch data to an case class via .as[CaseClass] or insert data from case classes (will be translated to a Map[String,_]
 - Lazy evaluation, all expressions are evaluated in a lazy fashion, meaning that the ast will be build up but the inner `args` and `optargs` wont be resolved until .run/.as or .ast is called for performance.
 - Importing com.rethinkscala.Implicits._ will give you a more normal way to construct your rql so you can write normal scala code without worrying about casting in a `Typed` or via `Expr`
 - Uses Jackson for json mapping via [jackson-module-scala](https://github.com/FasterXML/jackson-module-scala) 
 

TODO

  - Complete Test Suite
  - Implement Cursor Response (this should have the same interface as a Seq/Iterable),not that the `Connection` class will need
  to be updated so that it knows which connection to reuse to fetch more data.
  - Fix compile warns


Examples
```scala
scala> import com.rethinkscala.r
import com.rethinkscala.r

scala> r.db("foo").table("bar").get("batman")
res0: com.rethinkscala.ast.Get = Get(Table(bar,Some(false),Some(DB(foo))),batman)


scala> import com.rethinkscala.Implicits._
import com.rethinkscala.Implicits._

scala> r.table("marvel").map((hero:Var)=> hero \ "combatPower" + hero \ "combatPower" * 2)
res2: com.rethinkscala.ast.RMap = RMap(Table(marvel,None,None),Predicate1(<function1>))


scala> import com.rethinkscala.ast._
import com.rethinkscala.ast._

scala> import com.rethinkscala._
import com.rethinkscala._

scala> val version =new Version1("172.16.2.45")
version: com.rethinkscala.Version1 = Version1(172.16.2.45,28015,None,5)

scala> implicit val connection = new Connection(version)
connection: com.rethinkscala.Connection = Connection(Version1(172.16.2.45,28015,None,5))

scala> val info =DB("foo").table("bar").info
info: com.rethinkscala.ast.Info = Info(Table(bar,Some(false),Some(DB(foo))))

//case class DBResult(name: String, @JsonProperty("type") kind: String) extends Document
//case class TableInfoResult(name: String, @JsonProperty("type") kind: String, db: DBResult) extends Document

scala> val result = info.as[TableInfoResult]
result: Either[com.rethinkscala.RethinkError,com.rethinkscala.TableInfoResult] = Right(TableInfoResult(bar,TABLE,DBResult(test,DB)))
```



Installation
--------------
You need to pull down the modified ScalaBuff until changes are pushed to Sonatype


```sh
git clone git@github.com:kclay/ScalaBuff.git 
cd ScalaBuff
sbt
publish-local
project scalabuff-runtime
publish-local

Checkout Main repo
git clone git@github.com:kclay/rethink-scala.git
cd rethink-scala
sbt compile

```

Version
-

0.1 - Initial release
