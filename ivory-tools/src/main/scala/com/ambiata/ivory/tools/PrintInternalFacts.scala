package com.ambiata.ivory.tools

import java.io.File
import scalaz.concurrent.Task
import scalaz.syntax.traverse._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.{-\/, \/-}
import scalaz.stream.{Sink, io, Process}
import scalaz.stream.Process.End
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration
import com.ambiata.mundane.io.{IOActions, IOAction, Logger}
import IOActions._

import com.ambiata.ivory.core.Value
import com.ambiata.ivory.core.thrift.ThriftFact
import com.ambiata.ivory.storage.legacy.PartitionFactThriftStorageV2
import com.ambiata.ivory.scoobi.{ScoobiAction, SeqSchemas}
import com.ambiata.ivory.alien.hdfs.Hdfs

/**
 * Read a facts sequence file and print it to screen
 */
object PrintInternalFacts {

  def print(paths: List[Path], config: Configuration, delim: String, tombstone: String): IOAction[Unit] = for {
    l <- IOActions.ask
    _ <- Print.printPathsWith(paths, config, SeqSchemas.thriftFactSeqSchema, printFact(delim, tombstone, l))
  } yield ()

  def printFact(delim: String, tombstone: String, logger: Logger)(path: Path, f: ThriftFact): Task[Unit] = Task.delay {
    val logged = PartitionFactThriftStorageV2.parseFact(path.toString, f) match {
      case -\/(perr) => Some(s"Error - ${perr.message}, line ${perr.line}")
      case \/-(f) =>
        // We're ignoring structs here
        Value.toString(f.value, Some(tombstone)).map { value =>
          Seq(f.entity,
            f.namespace,
            f.feature,
            value,
            f.date.hyphenated + delim + f.time.hhmmss).mkString(delim)
        }
    }
    logged.foreach(logger(_).unsafePerformIO())
  }
}
