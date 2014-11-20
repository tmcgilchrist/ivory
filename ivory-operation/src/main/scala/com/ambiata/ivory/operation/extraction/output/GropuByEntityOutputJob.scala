package com.ambiata.ivory.operation.extraction.output

import com.ambiata.ivory.core._
import com.ambiata.ivory.lookup._
import com.ambiata.ivory.operation.extraction.output.GroupByEntityFormat._
import com.ambiata.ivory.storage.lookup._
import com.ambiata.poacher.mr._

import java.lang.{Iterable => JIterable}

import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf._
import org.apache.hadoop.io._
import org.apache.hadoop.io.compress._
import org.apache.hadoop.mapreduce.{Counter => _, _}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, SequenceFileOutputFormat, TextOutputFormat}

/**
 * This is a hand-coded MR job to squeeze the most out of performance.
 */
object GropuByEntityOutputJob {

  def run(conf: Configuration, dictionary: Dictionary, input: Path, output: Path, format: GroupByEntityFormat,
          reducers: Int, codec: Option[CompressionCodec]): Unit = {

    val job = Job.getInstance(conf)
    val name = format match {
      case DenseText(_, _) => "dense-text"
      case DenseThrift => "dense-thrift"
      case SparseThrift  => "sparse-thrift"
    }
    val ctx = MrContext.newContext("ivory-" + name, job)

    job.setJarByClass(classOf[GroupByEntityMapper])
    job.setJobName(ctx.id.value)

    // input
    job.setInputFormatClass(classOf[SequenceFileInputFormat[_, _]])
    FileInputFormat.addInputPaths(job, input.toString)

    // map
    job.setMapperClass(classOf[GroupByEntityMapper])
    job.setMapOutputKeyClass(classOf[BytesWritable])
    job.setMapOutputValueClass(classOf[BytesWritable])

    // partition & sort
    job.setPartitionerClass(classOf[GroupByEntityPartitioner])
    job.setGroupingComparatorClass(classOf[GroupByEntityComparator])
    job.setSortComparatorClass(classOf[BytesWritable.Comparator])

    // reducer
    job.setNumReduceTasks(reducers)
    job.setOutputKeyClass(classOf[NullWritable])

    // output
    val tmpout = new Path(ctx.output, "dense")
    val (delim, missing) = format match {
      case DenseText(delim, missing) =>
        job.setReducerClass(classOf[DenseReducerText])
        job.setOutputFormatClass(classOf[TextOutputFormat[_, _]])

        // We only need these for text output
        job.getConfiguration.set(Keys.Missing, missing)
        job.getConfiguration.set(Keys.Delimiter, delim.toString)
        (delim, Some(missing))
      case DenseThrift =>
        job.setReducerClass(classOf[DenseReducerThriftList])
        job.setOutputValueClass(classOf[BytesWritable])
        job.setOutputFormatClass(classOf[SequenceFileOutputFormat[_, _]])
        ('|', None)
      case SparseThrift =>
        job.setReducerClass(classOf[DenseReducerThriftMap])
        job.setOutputValueClass(classOf[BytesWritable])
        job.setOutputFormatClass(classOf[SequenceFileOutputFormat[_, _]])
        ('|', None)
    }
    FileOutputFormat.setOutputPath(job, tmpout)

    // compression
    codec.foreach(cc => {
      Compress.intermediate(job, cc)
      Compress.output(job, cc)
    })

    // cache / config initializtion

    val (_, lookup) = ReducerLookups.indexDefinitions(dictionary.sortedByFeatureId)
    ctx.thriftCache.push(job, Keys.FeatureIds, lookup)

    // run job
    if (!job.waitForCompletion(true))
      sys.error("ivory dense failed.")

    Committer.commit(ctx, {
      case "dense" => output
    }, true).run(conf).run.unsafePerformIO()

    DictionaryOutput.writeToHdfs(output, dictionary, missing, delim).run(conf).run.unsafePerformIO()
    ()
  }

  object Keys {
    val Missing = "ivory.dense.missing"
    val Delimiter = "ivory.dense.delimiter"
    val FeatureIds = ThriftCache.Key("ivory.dense.lookup.featureid")
  }
}

/**
 * Mapper for ivory-dense.
 *
 * The input is a standard SequenceFileInputFormat. Where the key is empty and the value
 * is thrift encoded bytes of a NamespacedThriftFact.
 *
 * The output key is the entity id and namespace.
 * The output value is the same as the input value.
 */
class GroupByEntityMapper extends Mapper[NullWritable, BytesWritable, BytesWritable, BytesWritable] {
  /** Thrift deserializer. */
  val serializer = ThriftSerialiser()

  /** Empty Fact, created once per reducer and mutated per record */
  val fact = createMutableFact

  /** Output key container */
  val kout = new BytesWritable

  /** Indexed view of dictionary for this run, see #setup. */
  val features = new FeatureIdLookup

  override def setup(context: Mapper[NullWritable, BytesWritable, BytesWritable, BytesWritable]#Context): Unit = {
    val ctx = MrContext.fromConfiguration(context.getConfiguration)
    ctx.thriftCache.pop(context.getConfiguration, GropuByEntityOutputJob.Keys.FeatureIds, features)
  }

  /** Read and pass through, extracting entity and feature id for sort phase. */
  override def map(key: NullWritable, value: BytesWritable, context: Mapper[NullWritable, BytesWritable, BytesWritable, BytesWritable]#Context): Unit = {
    serializer.fromBytesViewUnsafe(fact, value.getBytes, 0, value.getLength)
    val entity = fact.entity.getBytes("UTF-8")
    val required = entity.length + 4
    if (kout.getCapacity < required)
      kout.setCapacity(required * 2)
    kout.setSize(required)
    val bytes = kout.getBytes
    System.arraycopy(entity, 0, bytes, 0, entity.length)
    ByteWriter.writeInt(bytes, features.getIds.get(fact.featureId.toString), entity.length)
    context.write(kout, value)
  }
}

/**
 * Reducer for ivory-dense.
 *
 * This reducer takes the latest namespaced fact, in entity|namespace|attribute order.
 *
 * The input values are serialized containers of namespaced thrift facts.
 *
 * The output is a PSV text file.
 */
trait DenseReducer[A] extends Reducer[BytesWritable, BytesWritable, NullWritable, A] {

  /** Thrift deserializer */
  val serializer = ThriftSerialiser()

  /** Empty Fact, created once per reducer and mutated per record */
  val fact = createMutableFact

  /** Output key, only create once per reducer */
  val kout = NullWritable.get

  var features: Array[String] = null

  val vout: A
  val out: DenseFactOutput[A]

  override def setup(context: Reducer[BytesWritable, BytesWritable, NullWritable, A]#Context): Unit = {
    import scala.collection.JavaConverters._
    val lookup = new FeatureIdLookup
    val ctx = MrContext.fromConfiguration(context.getConfiguration)
    ctx.thriftCache.pop(context.getConfiguration, GropuByEntityOutputJob.Keys.FeatureIds, lookup)
    features = lookup.getIds.asScala.toList.sortBy(_._2).map(_._1).toArray
  }

  override def reduce(key: BytesWritable, iterable: JIterable[BytesWritable], context: Reducer[BytesWritable, BytesWritable, NullWritable, A]#Context): Unit = {
    out.clear()
    var first = true
    val iter = iterable.iterator
    var i = 0; while (iter.hasNext) {
      val next = iter.next
      serializer.fromBytesViewUnsafe(fact, next.getBytes, 0, next.getLength)

      if (first) {
        out.outputEntity(fact.entity)
      }

      while (i < features.length && features(i) != fact.featureId.toString) {
        out.outputMissing()
        i += 1
      }

      if (i <= features.length) {
        out.outputValue(features(i), fact)
      }
      first = false
      i += 1
    }
    while (i < features.length) {
      out.outputMissing()
      i += 1
    }

    out.write(vout)
    context.write(kout, vout)
  }
}

trait DenseFactOutput[A] {

  def clear(): Unit
  def outputEntity(entity: String): Unit
  def outputMissing(): Unit
  def outputValue(s: String, fact: Fact): Unit
  def write(vout: A): Unit
}

class DenseReducerText extends DenseReducer[Text] {

  /** Output value, only create once per reducer */
  val vout = new Text

  /** missing value to use in place of empty values. */
  var missing: String = null

  /** delimiter value to use in output. */
  var delimiter = '?'

  override def setup(context: Reducer[BytesWritable, BytesWritable, NullWritable, Text]#Context): Unit = {
    super.setup(context)
    missing = context.getConfiguration.get(GropuByEntityOutputJob.Keys.Missing)
    delimiter = context.getConfiguration.get(GropuByEntityOutputJob.Keys.Delimiter).charAt(0)
  }

  val out = new DenseFactOutput[Text] {

    /** Running output buffer for a row. */
    val buffer = new StringBuilder(4096)

    def clear(): Unit = {
      buffer.clear()
    }

    def outputEntity(entity: String): Unit = {
      buffer.append(entity)
      ()
    }

    def outputMissing(): Unit = {
      buffer.append(delimiter)
      buffer.append(missing)
      ()
    }

    def outputValue(featureId: String, fact: Fact): Unit = {
      buffer.append(delimiter)
      // Values can now be structs due to expressions
      buffer.append(Value.toStringWithStruct(fact.value, missing))
      ()
    }

    def write(vout: Text): Unit = {
      vout.set(buffer.toString())
    }
  }
}

import com.ambiata.ivory.operation.ingestion.thrift._

class DenseReducerThriftList extends DenseReducer[BytesWritable] {

  val vout = Writables.bytesWritable(4096)

  val out = new DenseFactOutput[BytesWritable] {

    val tfact = new ThriftFactDense()
    val list = new java.util.ArrayList[ThriftFactValue](1024)
    val missing = ThriftFactValue.tombstone(new ThriftTombstone())

    def clear(): Unit = {
      tfact.clear()
      list.clear()
    }

    def outputEntity(entity: String): Unit = {
      tfact.setEntity(fact.entity)
      ()
    }

    def outputMissing(): Unit = {
      list.add(missing)
      ()
    }

    def outputValue(featureId: String, fact: Fact): Unit = {
      list.add(Conversion.value2thrift(fact.toThrift.getValue))
      ()
    }

    def write(vout: BytesWritable): Unit = {
      tfact.setValue(list)
      val bytes = serializer.toBytes(tfact)
      vout.set(bytes, 0, bytes.length)
    }
  }
}

class DenseReducerThriftMap extends DenseReducer[BytesWritable] {

  val vout = Writables.bytesWritable(4096)

  val out = new DenseFactOutput[BytesWritable] {

    val tfact = new ThriftFactSparse()
    val map = new java.util.HashMap[String, ThriftFactValue]

    def clear(): Unit = {
      tfact.clear()
      map.clear()
    }

    def outputEntity(entity: String): Unit = {
      tfact.setEntity(fact.entity)
      ()
    }

    // Nothing to do - we leave off this feature
    def outputMissing(): Unit = ()

    def outputValue(featureId: String, fact: Fact): Unit = {
      // We don't distinguish missing from tombstones
      if (!fact.isTombstone) {
        map.put(featureId, Conversion.value2thrift(fact.toThrift.getValue))
      }
      ()
    }

    def write(vout: BytesWritable): Unit = {
      tfact.setValue(map)
      val bytes = serializer.toBytes(tfact)
      vout.set(bytes, 0, bytes.length)
    }
  }
}

/** Group by just the entity and ignore featureId */
class GroupByEntityComparator extends RawBytesComparator {
  def compareRaw(bytes1: Array[Byte], offset1: Int, length1: Int, bytes2: Array[Byte], offset2: Int, length2: Int): Int =
    compareBytes(bytes1, offset1, length1 - 4, bytes2, offset2, length2 - 4)
}

/** Partition by just the entity and ignore featureId */
class GroupByEntityPartitioner extends Partitioner[BytesWritable, BytesWritable] {
  override def getPartition(k: BytesWritable, v: BytesWritable, partitions: Int): Int =
    (WritableComparator.hashBytes(k.getBytes, 0, k.getLength - 4) & 0x7fffffff) % partitions
}