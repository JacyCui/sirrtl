package firrtl
package ir

// This is a singleton object that holds the security relation on security 
// levels. The policy is an extensional definition of the covered-by relation. 
// Each level is mapped to the set of levels it is covered by (i.e., 
// immediately lower than on the Hasse diagram of the lattice).
object PolicyHolder {
  var policy = new Lattice[Level]{
    def covers : Map[Level,Set[Level]] = Map(
      Level("L") -> Set(Level("D1"),Level("D2")),
      Level("D1") -> Set(Level("H")),
      Level("D2") -> Set(Level("H")),
      Level("H") -> Set()
    )
  }
}

abstract class Lattice[T] {
  // adjacency list representation of the covers relation
  // This implementation is based on Chapter 2.3 of:
  // Lattice Theory with Applications by Vijay K. Garg
  // Department of ECE at UT Austin.

  // covers(x) = { y | y covers x }
  def covers : Map[T,Set[T]]

  // Does a DFS of covers producing a set of elements reachable from x.
  def reachable(x: T) : Set[T] =
    Set[T](x) ++ covers(x).map(reachable(_)).fold(Set[T]())(_++_)

  // Set of elements from which x can be reached
  def canReach(x: T) : Set[T] =
    Set[T](x) ++ covers.keys.filter{ reachable(_).contains(x) }

  def leq(x: T, y: T) = reachable(x).contains(y)

  def join(x: T, y: T) : T = {
    val upper = reachable(x) & reachable(y)
    val candidates = upper.map{ e => (e, (canReach(e) & upper).size)}.filter{
      _._2 == 1}.map{ _._1 }
    candidates.iterator.next
  }

  def meet(x: T, y: T) : T = {
    val lower = canReach(x) & canReach(y)
    val candidates = lower.map{ e => (e, (reachable(e) & lower).size)}.filter{
      _._2 == 1}.map{ _._1 }
    candidates.iterator.next
  }

  def bottom = covers.keys.fold(covers.keys.head)(meet(_,_))
  def top = covers.keys.fold(covers.keys.head)(join(_,_))

  // Helper methods for readability
  def levels = covers.keys

}

// This is vestigial code that was used to test the lattice.

// object test extends App {
//   val lat = new Lattice[String]{
//     val covers : Map[String,Set[String]] = Map(
//       "l" -> Set("m1","m2"),
//       "m1" -> Set("d1"),
//       "m2" -> Set("d2"),
//       "d1" -> Set("h"),
//       "d2" -> Set("h"),
//       "h" -> Set()
//     )
//   }
//   val elements = Set("l","m1","m2","d1","d2","h")
//   elements.foreach(println(_))
//   elements.foreach{ i : String =>
//     println(lat.reachable(i).toString())
//   }
//   var pairs = Set[(String, String)]()
//   for(i <- elements; j <- elements) pairs ++= Set((i, j))
//   pairs.foreach{ case (e1, e2) =>
//     println(s"${e1} join ${e2} = ${lat.join(e1,e2)}")
//   }
//   pairs.foreach{ case (e1, e2) =>
//     println(s"${e1} meet ${e2} = ${lat.meet(e1,e2)}")
//   }
//   println(s"bottom: ${lat.bottom}")
//   println(s"top: ${lat.top}")
// }
// 
// test.main(args)
// 
// class MyLattice extends Lattice {
//   "l" <= "d1"
//   "l" <= "d2"
//   "d1" <= "h"
//   "d2" <= "h"
// }
