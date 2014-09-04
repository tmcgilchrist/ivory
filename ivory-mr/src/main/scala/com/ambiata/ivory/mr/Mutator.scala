package com.ambiata.ivory.mr

import org.apache.hadoop.io.BytesWritable

/**
 * Very poor man's way of processing a stream of values which will mutate a single value over a stream of values.
 * This is an attempt to reach a compromise between re-usability/testability and the harsh realities of performance.
 */
trait MutableStream[T, I] {

  /**
   * Convert the next value from a stream and update the in-memory representation value.
   */
  def from(in: I, value: T): Unit
}

trait PipeMutator[I, O] {

  def pipe(in: I, out: O): Unit
}

import com.ambiata.ivory.core._
import com.ambiata.ivory.core.thrift._

class ThriftByteMutator[T](implicit ev: T <:< ThriftLike)
  extends MutableStream[T, BytesWritable] with PipeMutator[BytesWritable, BytesWritable] {

  val serializer = ThriftSerialiser()

  def from(in: BytesWritable, thrift: T): Unit = {
    serializer.fromBytesViewUnsafe(thrift, in.getBytes, 0, in.getLength)
    ()
  }

  def pipe(in: BytesWritable, vout: BytesWritable): Unit =
    // We are saving a minor step of serialising the (unchanged) thrift fact
    vout.set(in.getBytes, 0, in.getLength)
}

/**
 * The most common mutation case which is that we are mutating a single thrift object.
 */
class FactByteMutator extends ThriftByteMutator[MutableFact]
