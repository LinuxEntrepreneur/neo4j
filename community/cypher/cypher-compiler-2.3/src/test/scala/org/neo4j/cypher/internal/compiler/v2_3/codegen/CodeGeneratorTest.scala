/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.codegen

import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.neo4j.collection.primitive.PrimitiveLongIterator
import org.neo4j.cypher.internal.compiler.v2_3.ast._
import org.neo4j.cypher.internal.compiler.v2_3.executionplan.ExecutionPlanBuilder.tracer
import org.neo4j.cypher.internal.compiler.v2_3.executionplan.InternalExecutionResult
import org.neo4j.cypher.internal.compiler.v2_3.pipes.LazyLabel
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_3.planner.{LogicalPlanningTestSupport, SemanticTable}
import org.neo4j.cypher.internal.compiler.v2_3.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_3.{CostBasedPlannerName, NormalMode, ParameterNotFoundException, TaskCloser}
import org.neo4j.graphdb.Result.{ResultRow, ResultVisitor}
import org.neo4j.graphdb.{Direction, GraphDatabaseService, Node}
import org.neo4j.helpers.Clock
import org.neo4j.kernel.api.ReadOperations
import org.neo4j.kernel.impl.api.RelationshipVisitor
import org.neo4j.kernel.impl.api.store.RelationshipIterator

import scala.collection.JavaConverters

class CodeGeneratorTest extends CypherFunSuite with LogicalPlanningTestSupport {

  private val generator = new CodeGenerator()

  test("all nodes scan") { // MATCH a RETURN a
    //given
    val plan = ProduceResult(List("a"), List.empty, List.empty,
      Projection(AllNodesScan(IdName("a"), Set.empty)(solved), Map("a" -> ident("a")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a")
    result should equal(List(
      Map("a" -> aNode),
      Map("a" -> bNode),
      Map("a" -> cNode),
      Map("a" -> dNode),
      Map("a" -> eNode),
      Map("a" -> fNode),
      Map("a" -> gNode)))
  }

  test("label scan") {// MATCH (a:T1) RETURN a
    //given
    val plan = ProduceResult(List("a"), List.empty, List.empty,
      Projection(NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved), Map("a" -> ident("a")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a")
    result should equal(List(
      Map("a" -> aNode),
      Map("a" -> bNode),
      Map("a" -> cNode)
    ))
    println(compiled.executionPlanDescription())
  }

  test("hash join of all nodes scans") { // MATCH a RETURN a
    //given
    val lhs = AllNodesScan(IdName("a"), Set.empty)(solved)
    val rhs = AllNodesScan(IdName("a"), Set.empty)(solved)
    val join = NodeHashJoin(Set(IdName("a")), lhs, rhs)(solved)
    val plan = ProduceResult(List("a"), List.empty, List.empty,
      Projection(join, Map("a" -> ident("a")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a")
    result should equal(List(
      Map("a" -> aNode),
      Map("a" -> bNode),
      Map("a" -> cNode),
      Map("a" -> dNode),
      Map("a" -> eNode),
      Map("a" -> fNode),
      Map("a" -> gNode)))
  }

  test("cartesian product of two label scans") {
    //given
    val lhs = NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved)
    val rhs = NodeByLabelScan(IdName("b"), LazyLabel("T2"), Set.empty)(solved)
    val join = CartesianProduct(lhs, rhs)(solved)
    val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
                             Projection(join, Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")
    result should equal(List(
      Map("a" -> aNode, "b" -> fNode),
      Map("a" -> aNode, "b" -> gNode),
      Map("a" -> bNode, "b" -> fNode),
      Map("a" -> bNode, "b" -> gNode),
      Map("a" -> cNode, "b" -> fNode),
      Map("a" -> cNode, "b" -> gNode)))
  }

  test("all nodes scan + expand") { // MATCH (a)-[r]->(b) RETURN a, b
    //given
    val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
      Projection(
        Expand(
          AllNodesScan(IdName("a"), Set.empty)(solved), IdName("a"), Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r"), ExpandAll)(solved),
        Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result should equal(List(
      Map("a" -> aNode, "b" -> dNode),
      Map("a" -> bNode, "b" -> dNode),
      Map("a" -> cNode, "b" -> eNode),
      Map("a" -> fNode, "b" -> dNode),
      Map("a" -> gNode, "b" -> eNode)))
  }

  test("label scan + expand outgoing") { // MATCH (a:T1)-[r]->(b) RETURN a, b
    //given
    val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
      Projection(
        Expand(
          NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved), IdName("a"),
          Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r"), ExpandAll)(solved),
        Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result should equal(List(
      Map("a" -> aNode, "b" -> dNode),
      Map("a" -> bNode, "b" -> dNode),
      Map("a" -> cNode, "b" -> eNode)
    ))
  }

  test("all node scan+ expand outgoing with one type") { // MATCH (a)-[r:R1]->(b) RETURN a, b
  //given
  val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
      Projection(
        Expand(
          AllNodesScan(IdName("a"), Set.empty)(solved), IdName("a"), Direction.OUTGOING, Seq(RelTypeName("R1")(null)), IdName("b"), IdName("r"), ExpandAll)(solved),
        Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result should equal(List(
      Map("a" -> aNode, "b" -> dNode),
      Map("a" -> bNode, "b" -> dNode),
      Map("a" -> cNode, "b" -> eNode)
    ))
  }

  test("all node scan+ expand outgoing with multiple types") { // MATCH (a)-[r:R1|R2]->(b) RETURN a, b
  //given
  val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
      Projection(
        Expand(
          AllNodesScan(IdName("a"), Set.empty)(solved), IdName("a"), Direction.OUTGOING,
          Seq(RelTypeName("R1")(pos), RelTypeName("R2")(pos)), IdName("b"), IdName("r"), ExpandAll)(solved),
          Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result should equal(List(
      Map("a" -> aNode, "b" -> dNode),
      Map("a" -> bNode, "b" -> dNode),
      Map("a" -> cNode, "b" -> eNode),
      Map("a" -> fNode, "b" -> dNode),
      Map("a" -> gNode, "b" -> eNode)))
  }

  test("label scan + expand incoming") { // // MATCH (a:T1)<-[r]-(b) RETURN a, b
  //given
  val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
              Projection(
                Expand(
                  NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved), IdName("a"),
                  Direction.INCOMING, Seq.empty, IdName("b"), IdName("r"), ExpandAll)(solved),
                  Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result shouldBe empty
  }

  test("label scan + expand both directions") { // MATCH (a:T1)-[r]-(b) RETURN a, b
  //given
  val plan = ProduceResult(List("a", "b"), List.empty, List.empty,
    Projection(
      Expand(
        NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved), IdName("a"), Direction.BOTH,
        Seq.empty, IdName("b"), IdName("r"), ExpandAll)(solved),
      Map("a" -> ident("a"), "b" -> ident("b")))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b")

    result should equal(List(
      Map("a" -> aNode, "b" -> dNode),
      Map("a" -> bNode, "b" -> dNode),
      Map("a" -> cNode, "b" -> eNode)
    ))
  }

  test("hash join on top of two expands from two all node scans") {
    // MATCH (a)-[r1]->(b)<-[r2]-(c) RETURN a,b,c (kind of nothing enforcing that r1 and r2 are distinct)

    //given
    val lhs = Expand(AllNodesScan(IdName("a"), Set.empty)(solved), IdName("a"), Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r1"), ExpandAll)(solved)
    val rhs = Expand(AllNodesScan(IdName("c"), Set.empty)(solved), IdName("c"), Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r2"), ExpandAll)(solved)
    val plan = ProduceResult(List("a", "b", "c"), List.empty, List.empty,
      Projection(
        NodeHashJoin(Set(IdName("b")), lhs, rhs)(solved), Map("a" -> ident("a"), "b" -> ident("b"), "c" -> ident("c"))
      )(solved))

    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b", "c")

    result.toSet should equal(Set(
      Map("a" -> aNode, "b" -> dNode, "c" -> fNode),
      Map("a" -> aNode, "b" -> dNode, "c" -> bNode),
      Map("a" -> aNode, "b" -> dNode, "c" -> aNode),
      Map("a" -> bNode, "b" -> dNode, "c" -> aNode),
      Map("a" -> bNode, "b" -> dNode, "c" -> bNode),
      Map("a" -> bNode, "b" -> dNode, "c" -> fNode),
      Map("a" -> fNode, "b" -> dNode, "c" -> aNode),
      Map("a" -> fNode, "b" -> dNode, "c" -> bNode),
      Map("a" -> fNode, "b" -> dNode, "c" -> fNode),
      Map("a" -> cNode, "b" -> eNode, "c" -> cNode),
      Map("a" -> cNode, "b" -> eNode, "c" -> gNode),
      Map("a" -> gNode, "b" -> eNode, "c" -> cNode),
      Map("a" -> gNode, "b" -> eNode, "c" -> gNode)
    ))
  }

  test("hash join on top of two expands from two label scans") {
    // MATCH (a:T1)-[r1]->(b)<-[r2]-(c:T2) RETURN b

    //given
    val lhs = Expand(NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved), IdName("a"),
      Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r1"), ExpandAll)(solved)
    val rhs = Expand(NodeByLabelScan(IdName("c"), LazyLabel("T2"), Set.empty)(solved), IdName("c"),
      Direction.OUTGOING, Seq.empty, IdName("b"), IdName("r2"), ExpandAll)(solved)
    val join = Projection(NodeHashJoin(Set(IdName("b")), lhs, rhs)(solved),
      Map("a" -> ident("a"), "b" -> ident("b"), "c" -> ident("c")))(solved)
    val plan = ProduceResult(List("a", "b", "c"), List.empty, List.empty, join)

    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a", "b", "c")

    result.toSet should equal(Set(
      Map("a" -> aNode, "b" -> dNode, "c" -> fNode),
      Map("a" -> bNode, "b" -> dNode, "c" -> fNode),
      Map("a" -> cNode, "b" -> eNode, "c" -> gNode)
    ))
  }


  test("hash join on top of hash join") {

    //given
    val scan1 = NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved)
    val scan2 = NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved)
    val scan3 = NodeByLabelScan(IdName("a"), LazyLabel("T1"), Set.empty)(solved)
    val join1 = NodeHashJoin(Set(IdName("a")), scan1, scan2)(solved)
    val join2 = NodeHashJoin(Set(IdName("a")), scan3, join1)(solved)
    val projection = Projection(join2, Map("a" -> ident("a")))(solved)
    val plan = ProduceResult(List("a"), List.empty, List.empty, projection)

    val compiled = compileAndExecute(plan)

    //then
    val result = getNodesFromResult(compiled, "a")

    result.toSet should equal(Set(
      Map("a" -> aNode),
      Map("a" -> bNode),
      Map("a" -> cNode)
    ))
  }

  test("project literal") {
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> SignedDecimalIntegerLiteral("1")(pos)))(solved))
    val compiled = compileAndExecute(plan)

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> 1)))
  }

  test("project parameter") {

    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> Parameter("FOO")(pos)))(solved))
    val compiled = compileAndExecute(plan, Map("FOO" -> "BAR"))

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> "BAR")))
  }

  test("project addition of two ints") {
    val lhs = SignedDecimalIntegerLiteral("1")(pos)
    val rhs = SignedDecimalIntegerLiteral("3")(pos)
    val add = Add(lhs, rhs)(pos)

    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> add))(solved))
    val compiled = compileAndExecute(plan)

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> 4)))
  }

  test("project subtraction of two ints") {
    val lhs = SignedDecimalIntegerLiteral("7")(pos)
    val rhs = SignedDecimalIntegerLiteral("5")(pos)
    val subtract = Subtract(lhs, rhs)(pos)

    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> subtract))(solved))
    val compiled = compileAndExecute(plan)

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> 2)))
  }

  test("project addition of int and double") {
    val lhs = SignedDecimalIntegerLiteral("1")(pos)
    val rhs = DecimalDoubleLiteral("3.0")(pos)
    val add = Add(lhs, rhs)(pos)

    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> add))(solved))
    val compiled = compileAndExecute(plan)

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> (1L + 3.0))))
  }

  test("project addition of int and String") {
    val lhs = SignedDecimalIntegerLiteral("1")(pos)
    val rhs = StringLiteral("two")(pos)
    val add = Add(lhs, rhs)(pos)

    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> add))(solved))
    val compiled = compileAndExecute(plan)

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> "1two")))
  }

  test("project addition of int and value from params") {
    val lhs = SignedDecimalIntegerLiteral("1")(pos)
    val rhs = Parameter("FOO")(pos)
    val add = Add(lhs, rhs)(pos)
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> add))(solved))
    val compiled = compileAndExecute(plan, Map("FOO" -> Long.box(3L)))

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> 4)))
  }

  test("project addition of two values coming from params") {
    val lhs = Parameter("FOO")(pos)
    val rhs = Parameter("BAR")(pos)
    val add = Add(lhs, rhs)(pos)
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> add))(solved))
    val compiled = compileAndExecute(plan, Map("FOO" -> Long.box(3L), "BAR" -> Long.box(1L)))

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> 4)))
  }

  test("project collection") {
    val collection = Collection(Seq(Parameter("FOO")(pos), Parameter("BAR")(pos)))(pos)
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> collection))(solved))
    val compiled = compileAndExecute(plan, Map("FOO" -> Long.box(3L), "BAR" -> Long.box(1L)))

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> List(3, 1))))
  }

  test("project map") {
    val map = MapExpression(Seq((PropertyKeyName("FOO")(pos), Parameter("BAR")(pos))))(pos)
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> map))(solved))
    val compiled = compileAndExecute(plan, Map("FOO" -> Long.box(3L), "BAR" -> Long.box(1L)))

    //then
    val result = getResult(compiled, "a")
    result.toSet should equal(Set(Map("a" -> Map("FOO" -> 1))))
  }

  test("close transaction after successfully exhausting result") {
    // given
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> SignedDecimalIntegerLiteral("1")(null)))(solved))

    // when
    val closer = mock[TaskCloser]
    val compiled = compileAndExecute(plan, taskCloser = closer)

    // then
    verifyZeroInteractions(closer)
    val visitor = mock[ResultVisitor[RuntimeException]]
    when(visitor.visit(any[ResultRow])).thenReturn(true)
    compiled.accept(visitor)
    verify(closer).close(success = true)
  }

  test("close transaction after prematurely terminating result exhaustion") {
    // given
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> SignedDecimalIntegerLiteral("1")(null)))(solved))

    // when
    val closer = mock[TaskCloser]
    val compiled = compileAndExecute(plan, taskCloser = closer)

    // then
    verifyZeroInteractions(closer)
    val visitor = mock[ResultVisitor[RuntimeException]]
    when(visitor.visit(any[ResultRow])).thenReturn(false)
    compiled.accept(visitor)
    verify(closer).close(success = true)
  }

  test("close transaction after failure while handling results") {
    // given
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> SignedDecimalIntegerLiteral("1")(null)))(solved))

    // when
    val closer = mock[TaskCloser]
    val compiled = compileAndExecute( plan, taskCloser = closer )

    // then
    verifyZeroInteractions(closer)
    val visitor = mock[ResultVisitor[RuntimeException]]
    val exception = new scala.RuntimeException()
    when(visitor.visit(any[ResultRow])).thenThrow(exception)
    intercept[RuntimeException] {
      compiled.accept(visitor)

    }
    verify(closer).close(success = false)
  }

  test("should throw the same error as the user provides") {
    // given
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> SignedDecimalIntegerLiteral("1")(null)))(solved))

    // when
    val closer = mock[TaskCloser]
    val compiled = compileAndExecute( plan, taskCloser = closer )

    // then
    val visitor = mock[ResultVisitor[RuntimeException]]
    val exception = new scala.RuntimeException()
    when(visitor.visit(any[ResultRow])).thenThrow(exception)
      try {
        compiled.accept(visitor)
        fail("should have thrown error")
      }
      catch {
        case e: Throwable => e should equal(exception)
      }

  }

  test("throw error when parameter is missing") {
    //given
    val plan = ProduceResult(List.empty, List.empty, List("a"), Projection(SingleRow()(solved), Map("a" -> Parameter("FOO")(pos)))(solved))

    //when
    val compiled = compileAndExecute(plan)

    //then
    intercept[ParameterNotFoundException](getResult(compiled, "a"))
  }

  test("handle line breaks and double quotes in names") {
    //given
    val name = """{"a":
              |1
              |}
            """.stripMargin
    val plan = ProduceResult(List.empty, List.empty, List(name), Projection(SingleRow()(solved),
      Map(name -> SignedDecimalIntegerLiteral("1")(pos)))(solved))

    //when
    val compiled = compileAndExecute(plan, Map("FOO" -> Long.box(3L), "BAR" -> Long.box(1L)))

    //then
    val result = getResult(compiled, name)
    result.toSet should equal(Set(Map(name -> 1)))
  }

  private def compile(plan: LogicalPlan) = {
    generator.generate(plan, newMockedPlanContext, Clock.SYSTEM_CLOCK, semanticTable, CostBasedPlannerName.default)
  }

  private def compileAndExecute(plan: LogicalPlan, params: Map[String, AnyRef] = Map.empty, taskCloser: TaskCloser = new TaskCloser) = {
    compile(plan).
    executionResultBuilder(statement, graphDatabaseService, NormalMode, tracer(NormalMode), params, taskCloser)
  }

  /*
   * Mocks the following graph:
   *
   * (a:T1) -[:R1] ->(d)<-[:R2]-(f:T2)
   *               ↗
   * (b:T1) -[:R1]
   *
   * (c:T1) -[:R1] ->(e)<-[:R2]-(g:T2)
   *
   */
  private val labelTokens = Map("T1" -> 1, "T2" -> 2)
  private val relTokens = Map("R1" -> 1, "R2" -> 2)

  private val aNode = mockNode(0L, "a")
  private val bNode = mockNode(1L, "b")
  private val cNode = mockNode(2L, "c")
  private val dNode = mockNode(3L, "d")
  private val eNode = mockNode(4L, "e")
  private val fNode = mockNode(5L, "f")
  private val gNode = mockNode(6L, "g")

  private val semanticTable =  mock[SemanticTable]
  when(semanticTable.isNode(ident("a"))).thenReturn(true)
  when(semanticTable.isNode(ident("b"))).thenReturn(true)
  when(semanticTable.isNode(ident("c"))).thenReturn(true)
  when(semanticTable.isNode(ident("d"))).thenReturn(true)
  when(semanticTable.isNode(ident("e"))).thenReturn(true)
  when(semanticTable.isNode(ident("f"))).thenReturn(true)
  when(semanticTable.isNode(ident("g"))).thenReturn(true)

  private val allNodes = Seq(aNode, bNode, cNode, dNode, eNode, fNode, gNode)
  private val nodesForLabel = Map("T1" -> Seq(aNode, bNode, cNode), "T2" -> Seq(fNode, gNode))

  private val relMap = Map(
    11L -> Relationship(aNode, dNode, 11L, 1),
    12L -> Relationship(bNode, dNode, 12L, 1),
    13L -> Relationship(cNode, eNode, 13L, 1),
    14L -> Relationship(fNode, dNode, 14L, 2),
    15L -> Relationship(gNode, eNode, 15L, 2))

  val ro = mock[ReadOperations]
  val statement = mock[org.neo4j.kernel.api.Statement]
  when(statement.readOperations()).thenReturn(ro)
  when(ro.nodesGetAll()).thenAnswer(new Answer[PrimitiveLongIterator] {
    override def answer(invocationOnMock: InvocationOnMock): PrimitiveLongIterator = primitiveIterator(allNodes.map(_.getId))
  })
  when(statement.readOperations().labelGetForName(anyString())).thenAnswer(new Answer[Int] {
    override def answer(invocationOnMock: InvocationOnMock): Int = {
      val label = invocationOnMock.getArguments.apply(0).asInstanceOf[String]
      labelTokens(label)
    }
  })
  when(statement.readOperations().relationshipTypeGetForName(anyString())).thenAnswer(new Answer[Int] {
    override def answer(invocationOnMock: InvocationOnMock): Int = {
      val label = invocationOnMock.getArguments.apply(0).asInstanceOf[String]
      relTokens(label)
    }
  })
  when(ro.nodesGetForLabel(anyInt())).thenAnswer(new Answer[PrimitiveLongIterator] {
    override def answer(invocationOnMock: InvocationOnMock): PrimitiveLongIterator = {
      val labelToken = invocationOnMock.getArguments.apply(0).asInstanceOf[Int]
      val (label, _) = labelTokens.find {
        case (l, t) => t == labelToken
      }.get
      val nodeIds = nodesForLabel(label).map(_.getId)
      primitiveIterator(nodeIds)
    }
  })
  when(ro.nodeGetRelationships(anyLong(), any[Direction])).thenAnswer(new Answer[PrimitiveLongIterator] {
    override def answer(invocationOnMock: InvocationOnMock): PrimitiveLongIterator = {
      val node = invocationOnMock.getArguments.apply(0).asInstanceOf[Long].toInt
      val dir = invocationOnMock.getArguments.apply(1).asInstanceOf[Direction]
      getRelsForNode(allNodes(node), dir, Set.empty)
    }
  })
  when(ro.nodeGetRelationships(anyLong(), any[Direction], anyVararg[Int]())).thenAnswer(new Answer[PrimitiveLongIterator] {
    override def answer(invocationOnMock: InvocationOnMock): PrimitiveLongIterator = {
      val arguments = invocationOnMock.getArguments
      val node = arguments(0).asInstanceOf[Long].toInt
      val dir = arguments(1).asInstanceOf[Direction]
      val types = (2 until arguments.length).map(arguments(_).asInstanceOf[Int]).toSet
      getRelsForNode(allNodes(node), dir, types)
    }
  })
  when(ro.relationshipVisit(anyLong(), any())).thenAnswer(new Answer[Unit] {
    override def answer(invocationOnMock: InvocationOnMock): Unit = {
      val relId = invocationOnMock.getArguments.apply(0).asInstanceOf[Long]
      val visitor = invocationOnMock.getArguments.apply(1).asInstanceOf[RelationshipVisitor[_]]
      val rel = relMap(relId)
      visitor.visit(relId, -1, rel.from.getId, rel.to.getId)
    }
  })
  val graphDatabaseService = mock[GraphDatabaseService]
  when(graphDatabaseService.getNodeById(anyLong())).thenAnswer(new Answer[Node]() {
    override def answer(invocationOnMock: InvocationOnMock): Node = {
      val id = invocationOnMock.getArguments.apply(0).asInstanceOf[Long].toInt
      allNodes(id)
    }
  })

  private def mockNode(id: Long, name: String) = {
    val node = mock[Node]
    when(node.getId).thenReturn(id)
    when(node.toString).thenReturn(name)
    node
  }

  private def getRelsForNode(node: Node, dir: Direction, types: Set[Int]) = {
    def hasType(relationship: Relationship) = types.isEmpty || types(relationship.relType)
    if (dir == Direction.OUTGOING) {
      val relIds = relMap.values.filter(n => n.from == node && hasType(n)).map(_.id).toSeq
      relationshipIterator(relIds)
    } else if (dir == Direction.INCOMING) {
      val relIds = relMap.values.filter(n => n.to == node && hasType(n)).map(_.id).toSeq
      relationshipIterator(relIds)
    } else {
      val relIds = relMap.values.filter(n => (n.from == node || n.to == node) && hasType(n)).map(_.id).toSeq
      relationshipIterator(relIds)
    }
  }

  case class Relationship(from: Node, to: Node, id: Long, relType: Int)

  private def primitiveIterator(longs: Seq[Long]) = new PrimitiveLongIterator {
    val inner = longs.toIterator

    override def next(): Long = inner.next()

    override def hasNext: Boolean = inner.hasNext
  }

  private def relationshipIterator(longs: Seq[Long]) = new RelationshipIterator {

    override def relationshipVisit[EXCEPTION <: Exception](relationshipId: Long, visitor: RelationshipVisitor[EXCEPTION]): Boolean = {
      val rel = relMap(relationshipId)
      visitor.visit(relationshipId, -1, rel.from.getId, rel.to.getId)
      false
    }

    val inner = longs.toIterator

    override def next(): Long = inner.next()

    override def hasNext: Boolean = inner.hasNext
  }

  private def getNodesFromResult(plan: InternalExecutionResult, columns: String*) = {
    val res = Seq.newBuilder[Map[String, Node]]

    plan.accept(new ResultVisitor[RuntimeException]() {
      override def visit(element: ResultRow): Boolean = {
        res += columns.map(col => col -> element.getNode(col)).toMap
        true
      }
    })
    res.result()
  }

  private def getResult(plan: InternalExecutionResult, columns: String*) = {
    val res = Seq.newBuilder[Map[String, Any]]

    plan.accept(new ResultVisitor[RuntimeException]() {
      override def visit(element: ResultRow): Boolean = {
        res += columns.map(col => col -> element.get(col)).toMap
        true
      }
    })
    res.result().asComparableResult
  }


  implicit class RichMapSeq(res: Seq[Map[String, Any]]) {
    import JavaConverters._

    def asComparableResult: Seq[Map[String, Any]] = res.map((map: Map[String, Any]) =>
      map.map {
        case (k, a: Array[_]) => k -> a.toList
        case (k, a: java.util.List[_]) => k -> a.asScala
        case (k, m: java.util.Map[_,_]) => k -> m.asScala
        case m => m
      }
    )
  }

}