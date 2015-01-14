/*
 * Copyright (c) 2014. Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.twitter.cassovary.graph

import com.twitter.cassovary.graph.StoredGraphDir._
import com.twitter.cassovary.util.NodeNumberer
import org.scalatest.WordSpec

trait GraphBehaviours {
  this: WordSpec =>

  private def correctNumberOfNodesAndEdges(graph: DirectedGraph, numNodes: Int) {
    "have correct number of nodes" in {
      assert(graph.nodeCount === numNodes)
    }
    "have number of edges less than in full graph" in {
      assert(graph.edgeCount <= (numNodes * (numNodes - 1).toLong))
    }
  }

  def completeGraph(graph: => DirectedGraph, numNodes: Int) {
    "have correct number of nodes" in {
      assert(graph.nodeCount === numNodes, "wrong number of nodes")
    }
    "have all possible edges" in {
      assert(graph.edgeCount === (numNodes * (numNodes - 1).toLong))
      graph foreach { node =>
        val neighbors = (0 until numNodes) filter {
          _ != node.id
        }
        assert(node.inboundNodes().sorted === neighbors)
        assert(node.outboundNodes().sorted === neighbors)
      }
    }
  }

  def correctDirectedGraph(graph: => DirectedGraph, numNodes: Int) {
    correctNumberOfNodesAndEdges(graph, numNodes)

    "be consistent among all nodes" in {
      graph foreach { node =>
        (0 to 1) foreach { dir =>
          val neighbors = if (dir == 0) node.outboundNodes() else node.inboundNodes()
          assert(neighbors.size === neighbors.toSet.size, "duplicates in neighbors array detected")
          neighbors foreach { nbr =>
            assert(nbr >= 0, "nbr.id < 0 (" + nbr + ")")
            assert(nbr < numNodes, "nbr.id >= numNodes (" + nbr + ")")
            assert(nbr != node.id, "self-loop in graph")
            val neighborNode = graph.getNodeById(nbr).get
            val neighborNodeOtherDirEdges =
              if (dir == 0) neighborNode.inboundNodes()
              else neighborNode.outboundNodes()
            assert(neighborNodeOtherDirEdges.contains(node.id), "edge existence not consistent in nodes")
          }
        }
      }
    }
  }

  val sampleGraphEdges = Map(1 -> Array(2,3,4), 2 -> Array(1), 3 -> Array(4), 5 -> Array(1, 10))

  def verifyInOutEdges(graph: DirectedGraph, numNodes: Int,
                       outEdges: Map[Int, Array[Int]],
                       inEdges: Map[Int, Array[Int]]): Unit = {

    def check(id: Int, a: Seq[Int], b: Option[Array[Int]]) {
      if (a.isEmpty) {
        assert(b.isEmpty || b.get.isEmpty,
          "graph's nodeid " + id + " is empty but supplied edges is not: " + b)
      } else {
        assert(b.isDefined, "graph's nodeid " + id + " is not empty but supplied edges is")
        assert(a.toSet == b.get.toSet, "graph's nodeid " + id + "'s edges do not match: in graph = " +
          a.toSet + " edges = " + b.get.toSet)
      }
    }

    correctNumberOfNodesAndEdges(graph, numNodes)
    for (node <- graph) {
      check(node.id, node.outboundNodes(), outEdges.get(node.id))
      check(node.id, node.inboundNodes(), inEdges.get(node.id))
    }
  }

  // verify a graph constructed from a supplied map of node id -> array of edges (outedges unless
  // OnlyIn direction is to be stored in which case the supplied edges are incoming edges)
  def verifyGraph(makeGraph: (() => Iterator[NodeIdEdgesMaxId], StoredGraphDir) => DirectedGraph,
                  givenEdges: Map[Int, Array[Int]]): Unit =
  {
    def cross(k: Int, s: Array[Int]) = for (e <- s) yield (e, k)

    val allIds: Set[Int] = givenEdges.keys.toSet ++ givenEdges.values.toSet.flatMap { x: Array[Int] => x }
    val noEdges = Map.empty[Int, Array[Int]]
    def iteratorFunc = () => { givenEdges map { case (k, s) => NodeIdEdgesMaxId(k, s) } toIterator }
    for (dir <- List(StoredGraphDir.BothInOut, StoredGraphDir.OnlyOut, StoredGraphDir.OnlyIn)) {
      val graph = makeGraph(iteratorFunc, dir)
      "Graph constructed in direction " + dir should {
        val inEdges: Map[Int, Array[Int]] = dir match {
          case StoredGraphDir.OnlyIn => givenEdges
          case StoredGraphDir.OnlyOut => noEdges
          case StoredGraphDir.BothInOut =>
            givenEdges.toArray flatMap { case (k, s) => cross(k, s) } groupBy(_._1) mapValues {_.map(_._2) }
        }
        verifyInOutEdges(graph, allIds.size,
          if (dir == StoredGraphDir.OnlyIn) noEdges else givenEdges, inEdges)
      }
    }
  }

  def correctUndirectedGraph(graph: => DirectedGraph, numNodes: Int) {
    correctNumberOfNodesAndEdges(graph, numNodes)

    "be consistent among all nodes" in {
      graph foreach { node =>
        assert(node.inboundNodes().sorted === node.outboundNodes().sorted, "inbound nodes not equal to outbound nodes" +
          "in undirected graph")
        assert(node.inboundNodes().size === node.inboundNodes().toSet.size)
        node.inboundNodes() foreach { nbr =>
          assert(nbr >= 0, "nbr.id < 0 (" + nbr + ")")
          assert(nbr < numNodes, "nbr.id >= numNodes (" + nbr + ")")
          assert(nbr != node.id, "node " + node.id + " contains self-loop")
          assert(graph.getNodeById(nbr).get.outboundNodes().contains(node.id), "edge existence not consistent in nodes")
        }
      }
    }
  }

  def graphEquivalentToMap(g: DirectedGraph, nodeMap: Map[Int, List[Int]]) = {
    g.foreach {
      node =>
        assert(nodeMap.contains(node.id) === true, "unexpected node: " + node.id)
        val neighbors = node.outboundNodes().toArray.sorted
        val nodesInMap = nodeMap(node.id).toArray.sorted
        assert(neighbors.length === nodesInMap.length, "node: " + node.id + " neighbors.length incorrect")
        neighbors.iterator.zip(nodesInMap.iterator).foreach {
          case (a, b) => assert(a === b, "node: " + node.id + " neighbors incorrect")
        }
    }
  }

  /**
   * Compares the nodes in a graph and those defined by the nodeMap (id -> ids of neighbors),
   * remapping node ids thru nodeRenumberer, and ensures that these are equivalent
   * @param g DirectedGraph
   * @param nodeMap Map of node ids to ids of its neighbors
   * @param nodeNumberer a node renumberer
   */
  def renumberedGraphEquivalentToMap[T](g: DirectedGraph, nodeMap: Map[T, List[T]], nodeNumberer: NodeNumberer[T]) = {
    g.foreach { node =>
      assert(nodeMap.contains(nodeNumberer.internalToExternal(node.id)) === true,
        "unexpected node in a graph: " + nodeNumberer.internalToExternal(node.id))
      val neighbors = node.outboundNodes()
      val nodesInMap = nodeMap(nodeNumberer.internalToExternal(node.id))
      assert(nodesInMap.forall { i => neighbors.contains(nodeNumberer.externalToInternal(i)) } === true,
        "edge missing in the graph")
      assert(neighbors.forall { i => nodesInMap.contains(nodeNumberer.internalToExternal(i)) } === true,
        "unexpected edge in the graph")
    }
    nodeMap.keys.foreach {
      id => assert(g.existsNodeId(nodeNumberer.externalToInternal(id)) === true, "node " + id + " missing in graph")
    }
  }
}
