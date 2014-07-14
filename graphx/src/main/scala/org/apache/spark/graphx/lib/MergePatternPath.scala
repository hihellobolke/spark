/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.graphx.lib

import scala.reflect.ClassTag
import org.apache.spark.graphx._
import scala.Iterator
import org.apache.spark.rdd.RDD

/*
 * This just a small enhancement to Ankur Dave's Pattern Match
 * added EdgeTriplet pattern matching, which takes a function
 * returns a new Graph, with vertices, and the new merge edge.
 * the vertices attributes are preserved...
 * 
 * -- G@ut.am
 */

object MergePatternPath {

  def run[VD: ClassTag, ED: ClassTag](
    graph: Graph[VD, ED],
    pattern: List[TripletPattern[VD, ED]],
    mergedEdgeAttr: String = "mergedEdge"): Graph[VD, String] = {

    val newGraph = graph.mapVertices((vid, attr) => (attr, Set[PartialPathMatch[VD, ED]]()))
    val emptyPartialMatch = Set(new PartialPathMatch(List.empty, pattern))

    val partialMatches = Pregel[(VD, Set[PartialPathMatch[VD, ED]]), ED, Set[PartialPathMatch[VD, ED]]](
      newGraph, emptyPartialMatch)(

        (vid, vdata, newPathMatches) => {
          val vdataWithoutPartialMatch = vdata._1
          val consolidatedMatches = vdata._2.filter(_.matched.length > 0) ++ newPathMatches
          (vdata._1, consolidatedMatches)
        },

        et => {
          val srcPartialMatchOrignalSet = et.srcAttr._2.toSet
          val dstPartialMatchOrignalSet = et.dstAttr._2.toSet

          val srcPartialMatchSet = srcPartialMatchOrignalSet.flatMap(_.tryMatch(et.srcId, et)).toSet
          val srcPM = srcPartialMatchOrignalSet.map(_.matched)

          val dstPartialMatchSet = dstPartialMatchOrignalSet.flatMap(_.tryMatch(et.dstId, et)).toSet
          val dstPM = dstPartialMatchOrignalSet.map(_.matched)

          val msgsForSrc = dstPartialMatchSet.filter(p => { if (srcPM contains p.matched) false else true })
          val msgsForDst = srcPartialMatchSet.filter(p => { if (dstPM contains p.matched) false else true })

          if (msgsForSrc.nonEmpty && msgsForDst.nonEmpty) {
            Iterator((et.srcId, msgsForSrc), (et.dstId, msgsForDst))
          } else if (msgsForSrc.nonEmpty) {
            Iterator((et.srcId, msgsForSrc))
          } else if (msgsForDst.nonEmpty) {
            Iterator((et.dstId, msgsForDst))
          } else {
            Iterator.empty
          }
        },

        (a, b) => {
          a ++ b
        })

    val q = partialMatches.vertices
    val p = q.flatMap(
      a => {
        val vid = a._1
        val pm = a._2._2.filter(_.isComplete).map(_.getCompletMatchSrc)
        pm match {
          case a if (a.isEmpty) => None;
          case _ => Some(pm.map(List(vid, _)).toList)
        }
      })
    val validNodeSet: Set[VertexId] = p.toArray.flatten.toSet.flatten
    val nodes: RDD[(VertexId, VD)] = q.flatMap(
      a => {
        a match {
          case (f, (g, h)) if validNodeSet.contains(a._1) => Some((f, g));
          case _ => None
        }
      })
    val edges: RDD[Edge[String]] = p.flatMap(
      _.flatMap(a1 => a1 match {
        case List(b, c) => Some(Edge(c, b, mergedEdgeAttr));
        case _ => None;
      }))
    Graph(nodes, edges)
  }
}

case class TripletPattern[VD, ED](
  tripletFilter: (EdgeTriplet[(VD, Set[PartialPathMatch[VD, ED]]), ED]) ⇒ Boolean = (_: EdgeTriplet[(VD, Set[PartialPathMatch[VD, ED]]), ED]) => { true },
  matchDstFirst: Boolean = false) extends Serializable {
  /**
   * OldMatches an edge against the OldClearEdgePattern,
   *  where we are currently traversing curVertex.
   */
  def matches(curVertex: VertexId, et: EdgeTriplet[(VD, Set[PartialPathMatch[VD, ED]]), ED]): Boolean = {
    val edgeMatches = tripletFilter(et)
    val directionMatches = if (matchDstFirst) curVertex == et.dstId else curVertex == et.srcId
    edgeMatches && directionMatches
  }
}

/** Within a successful OldMatch, represents a single OldMatched edge. */
case class TripletMatch[ED](srcId: VertexId, dstId: VertexId, attr: ED) extends Serializable {
  override def toString = s"$srcId --[$attr]--> $dstId"
}

/** Represents a successful OldMatch composed of a sequence of OldMatching edges. */
case class PathMatch[ED](path: List[TripletMatch[ED]]) extends Serializable

case class PartialPathMatch[VD, ED](
  matched: List[TripletMatch[ED]],
  remaining: List[TripletPattern[VD, ED]]) extends Serializable {
  /**
   * Attempts to OldMatch the given current vertex and edge triplet, returning an augmented
   * OldClearPartialOldMatch if successful.
   */
  def tryMatch(
    curVertex: VertexId,
    et: EdgeTriplet[(VD, Set[PartialPathMatch[VD, ED]]), ED]): Option[PartialPathMatch[VD, ED]] = {
    remaining match {
      case pattern :: rest if pattern.matches(curVertex, et) =>
        val newMatch = TripletMatch(et.srcId, et.dstId, et.attr)
        Some(PartialPathMatch(newMatch :: matched, rest));
      case _ => None
    }
  }

  def isAncestor(pm: PartialPathMatch[VD, ED]): Boolean = {
    val pmlen: Int = pm.matched.length
    val mlen: Int = matched.length
    matched match {
      case pm.matched if (pmlen > 0 && mlen > 0) =>
        true
      case pm.matched :: remaining if (pmlen > 0 && mlen > 0) =>
        true
      case _ =>
        false
    }
  }

  def isComplete: Boolean = remaining.isEmpty

  def toCompleteMatch: PathMatch[ED] = {
    assert(isComplete)
    new PathMatch(matched.reverse)
  }

  def getCompletMatchSrc: VertexId = {
    assert(isComplete)
    matched.last.srcId
  }
}
