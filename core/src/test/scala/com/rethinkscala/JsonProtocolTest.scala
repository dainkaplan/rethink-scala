package com.rethinkscala

import com.rethinkscala.net.{JsonAst, JsonCompiledAst}
import org.scalatest.FunSuite
import ql2.{Ql2=> ql2}

/**
 * Created with IntelliJ IDEA.
 * User: keyston
 * Date: 7/4/14
 * Time: 2:09 PM
 *
 */

case class JFoo(id:Option[String] = None) extends Document
class JsonProtocolTest  extends FunSuite with WithBase{



  test("ast"){


    println(version3.toAst(r.expr(1)).underlying.toValue)
    val t = r.tableAs[JFoo]("test").insert(Seq.empty)
    val compiled = version3.toQuery(t,1,None,Map.empty)
    println(compiled.json)



  }

  override def setupDB = false

  override def useVersion = version3
}
