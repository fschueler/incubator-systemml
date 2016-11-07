package org.apache.sysml.api.linalg.types

import org.apache.sysml.api.linalg.api._
import org.apache.sysml.api.linalg.Lazy._
import org.apache.sysml.api.linalg.{Lazy, Matrix}
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject

object TypeClass {

  //////////////////////////////////////////////////////////////////////////////////////////////
  // BLOCK TYPECLASS
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
    * Represents the actual physical single node representation of the data
    */

  // TODO have a superclass for blocks so that we can return less specific blocks

  trait Block[A] {
    def +(x: A, y: A): A
  }

  implicit class BlockOps[A](a: A)(implicit ev: Block[A]) {
    def +(y: A) = ev.+(a, y)
  }

  case class DenseBlock(values: Array[Double]) {
    override def toString: String = values.mkString(" ")
  }
  object DenseBlock {
    implicit val denseBlock = new Block[DenseBlock] {
      override def +(x: DenseBlock, y: DenseBlock): DenseBlock = {
        DenseBlock(x.values.zip(y.values).map(x => x._1 + x._2))
      }

      def +(x: DenseBlock, y: SparseBlock): DenseBlock = DenseBlock(Array())
    }
  }

  case class SparseBlock(values: Map[(Int, Int), Double])
  object SparseBlock {
    implicit val sparseBlock = new Block[SparseBlock] {
      override def +(x: SparseBlock, y: SparseBlock) = {
        val result = for (key <- x.values.keys ++ y.values.keys) yield
          key -> (x.values.getOrElse(key, 0.0) + y.values.getOrElse(key, 0.0))
        SparseBlock(result.toMap[(Int, Int), Double])
      }
    }
  }

  case class ZeroBlock()
  object ZeroBlock {
      implicit val zeroBlock = new Block[ZeroBlock] {
        override def +(x: ZeroBlock, y: ZeroBlock): ZeroBlock = new ZeroBlock()
      }
   }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Strategy TYPECLASS
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
    * Represents the layout of the blocks (distributed, local, etc.)
    */

  trait Strategy[A] {
    // add two layouts of the same type
    def +(x: A, y: A): A
    def +(x: A, y: Double): A

    def sum(x: A): EagerEval
  }

  implicit class StrategyOps[A](a: A)(implicit ev1: Strategy[A]) {
    def +(y: A) = ev1.+(a, y)
    def +(y: Double) = ev1.+(a, y)
  }

  case class EagerEval(impl: MatrixObject)

  object EagerEval {
    implicit val eagerEval = new Strategy[EagerEval] {

      override def +(x: EagerEval, y: EagerEval): EagerEval = ???

      override def +(x: EagerEval, y: Double): EagerEval = ???

      override def sum(x: EagerEval): EagerEval = ???
    }
  }

  case class LazyEval(impl: Tree) extends Lazy {

    def collect(): EagerEval = eval(impl)

    override def toString: String = impl.toString
  }

  object LazyEval {
    implicit val lazyEval = new Strategy[LazyEval] {
      override def +(x: LazyEval, y: LazyEval): LazyEval = LazyEval(BinOp("+", x.impl, y.impl))

      override def +(x: LazyEval, y: Double): LazyEval = LazyEval(BinOp("+", x.impl, Scalar(y)))

      override def sum(x: LazyEval): EagerEval = {
        val op = LazyEval(Application("sum", List(x.impl)))
        op.collect()
      }
    }
  }

//  case class Local[A: Block](impl: A) {
//    override def toString: String = "Local Matrix: \n" + impl.toString
//  }
//  object Local {
//    implicit def localLayout[A: Block] = new Strategy[Local[A]] {
//      override def +(x: Local[A], y: Local[A]): Local[A] = {
//        Local(x.impl + y.impl)
//      }
//
//      override def +(x: Local[A], y: Double): Local[A] = ???
//
//      override def collect(): EagerEval
//    }
//  }
//
//  case class Spark[A: Block](impl: RDD[((Int, Int), A)])
//  object Spark {
//    private val sc = new SparkContext(new SparkConf().setMaster("local[2]").setAppName("TypeclassTest"))
//
//    implicit def SparkLayout[A: Block : ClassTag]: Strategy[Spark[A]] = new Strategy[Spark[A]] {
//
//      override def +(x: Spark[A], y: Spark[A]): Spark[A] = {
//        def joined = x.impl.join(y.impl)
//        val mapped = joined.map { case (k, (v, w)) => (k, v + v)}
//        Spark(mapped)
//      }
//
//      override def +(x: Spark[A], y: Double): Spark[A] = ???
//    }
//
//    def apply(block: DenseBlock): Spark[DenseBlock] = Spark(sc.parallelize(Seq(((1, 1), block))))
//  }
}

