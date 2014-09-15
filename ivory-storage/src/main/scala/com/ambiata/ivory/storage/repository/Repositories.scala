package com.ambiata.ivory.storage.repository

import com.ambiata.ivory.core.Repository
import com.ambiata.ivory.core._
import com.ambiata.ivory.storage.metadata._
import com.ambiata.mundane.control._
import com.ambiata.mundane.store.Store

import scalaz.Scalaz._

object Repositories {

  val initialPaths = List(
    Repository.root,
    Repository.dictionaries,
    Repository.featureStores,
    Repository.factsets,
    Repository.snapshots,
    Repository.errors,
    Repository.commits
  )

  def create(repo: Repository): ResultTIO[Unit] = {
    val store: Store[ResultTIO] = repo.toStore
    for {
      e <- store.exists(Repository.root </> ".allocated")
      r <- ResultT.unless(e, for {
        _     <- initialPaths.traverse(p => store.utf8.write(p </> ".allocated", "")).void
        // Set the initial commit
        dict  <- DictionaryThriftStorage(repo).store(Dictionary.empty)
        store <- FeatureStoreTextStorage.increment(repo, List())
        _     <- Metadata.incrementCommit(repo, dict, store)
      } yield ())
    } yield r
  }
}
