package breeze.linalg

import breeze.generic.UFunc
import breeze.storage.DefaultArrayValue
import scala.reflect.ClassTag
import spire.implicits._

/**
 * split the array
 *
 * @author stucchio
 */
object split extends UFunc {

  implicit def implIntVec[T: ClassTag](implicit default: DefaultArrayValue[T]): Impl2[DenseVector[T], Int, Seq[DenseVector[T]]] = new Impl2[DenseVector[T], Int, Seq[DenseVector[T]]] {
    def apply(v: DenseVector[T], n: Int): Seq[DenseVector[T]] = {
      require(n >= 0)
      require(n < v.size)
      require(v.size % n == 0)

      val individualVectorSize = v.size / n
      val result = new collection.mutable.ListBuffer[DenseVector[T]]()

      cfor(0)(k => k < n, k => k+1)(k => {
        val offsetInOriginalVector = k*individualVectorSize
        val chunk = new Array[T](individualVectorSize)
        cfor(0)(i => i < individualVectorSize, i => i+1)(i => {
          chunk(i) = v.unsafeValueAt(offsetInOriginalVector+i)
        })
        result += new DenseVector[T](chunk)
      })
      result.toSeq
    }
  }

  implicit def implSeqVec[T: ClassTag](implicit default: DefaultArrayValue[T]): Impl2[DenseVector[T], Seq[Int], Seq[DenseVector[T]]] = new Impl2[DenseVector[T], Seq[Int], Seq[DenseVector[T]]] {
    def apply(v: DenseVector[T], nSeq: Seq[Int]): Seq[DenseVector[T]] = {
      require(nSeq.size < v.size)

      val result = new collection.mutable.ListBuffer[DenseVector[T]]()
      var lastN: Int = 0
      nSeq.foreach(n => {
        val chunk = new Array[T](n - lastN)
        cfor(lastN)(i => i < n, i => i + 1)(i => {
          chunk(i-lastN) = v.unsafeValueAt(i)
        })
        result += new DenseVector[T](chunk)
        lastN = n
      })
      if (lastN < v.size) { //If we did not already add last chunk to result, do it now.
        val chunk = new Array[T](v.size - lastN)
        cfor(lastN)(i => i < v.size, i => i + 1)(i => {
          chunk(i-lastN) = v.unsafeValueAt(i)
        })
        result += new DenseVector[T](chunk)
      }
      result.toSeq
    }
  }
}

object hsplit extends UFunc {
  implicit def implIntVec[T: ClassTag](implicit default: DefaultArrayValue[T]): Impl2[DenseVector[T], Int, Seq[DenseVector[T]]] = new Impl2[DenseVector[T], Int, Seq[DenseVector[T]]] { //For vectors just an alias
    def apply(v: DenseVector[T], n: Int): Seq[DenseVector[T]] = hsplit(v,n)
  }

  implicit def implSeqVec[T: ClassTag](implicit default: DefaultArrayValue[T]): Impl2[DenseVector[T], Seq[Int], Seq[DenseVector[T]]] = new Impl2[DenseVector[T], Seq[Int], Seq[DenseVector[T]]] { //For vectors just an alias
    def apply(v: DenseVector[T], n: Seq[Int]): Seq[DenseVector[T]] = hsplit(v,n)
  }
}
