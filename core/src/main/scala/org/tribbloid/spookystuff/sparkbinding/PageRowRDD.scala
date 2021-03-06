package org.tribbloid.spookystuff.sparkbinding

import java.util.UUID

import org.apache.spark.rdd.{RDD, UnionRDD}
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{HashPartitioner, SparkEnv}
import org.slf4j.LoggerFactory
import org.tribbloid.spookystuff.actions._
import org.tribbloid.spookystuff.dsl.{Inner, JoinType, _}
import org.tribbloid.spookystuff.entity.PageRow.Signature
import org.tribbloid.spookystuff.entity._
import org.tribbloid.spookystuff.expressions._
import org.tribbloid.spookystuff.pages.{PageLike, Unstructured}
import org.tribbloid.spookystuff.utils._
import org.tribbloid.spookystuff.{Const, QueryException, SpookyContext}

import scala.collection.immutable.ListSet
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

/**
 * Created by peng on 8/29/14.
 */
case class PageRowRDD(
                       @transient store: RDD[PageRow],
                       @transient keys: ListSet[KeyLike] = ListSet(),
                       @transient spooky: SpookyContext
                       )
  extends RDD[PageRow](store) with PageRowRDDOverrides {

  import org.apache.spark.SparkContext._

  def segmentBy(exprs: Expression[Any]*): PageRowRDD = { //TODO: need spike

    this.copy(store.map{
      row =>
        val ser = SparkEnv.get.serializer.newInstance()
        val values = exprs.map(_.apply(row))
        val buffer = ser.serialize(values)
        row.copy(segmentID = UUID.nameUUIDFromBytes(buffer.array()))
    })
  }

  def segmentByRow: PageRowRDD = {
    this.copy(store.map(_.copy(segmentID = UUID.randomUUID())))
  }

  private def discardPages: PageRowRDD = this.copy(store = this.map(_.copy(pageLikes = Seq())))

  @transient def keysSeq: Seq[KeyLike] = this.keys.toSeq.reverse

  @transient def sortKeysSeq: Seq[SortKey] = keysSeq.flatMap{
    case k: SortKey => Some(k)
    case _ => None
  }

  private def defaultOrder: PageRowRDD = {

    val sortKeysSeq = this.sortKeysSeq

    import scala.Ordering.Implicits._

    this.persistDuring(spooky.conf.defaultStorageLevel){
      val result = this.sortBy{_.ordinal(sortKeysSeq)}
      result.count()
      result
    }
  }

  def toMapRDD(sort: Boolean = true): RDD[Map[String, Any]] =
    if (!sort) this.map(_.toMap)
    else this
      .discardPages
      .defaultOrder
      .map(_.toMap)

  def toJSON(sort: Boolean = true): RDD[String] =
    if (!sort) this.map(_.toJSON)
    else this
      .discardPages
      .defaultOrder
      .map(_.toJSON)

  //TODO: investigate using the new applySchema api to avoid losing type info
  def toDataFrame(sort: Boolean = true, tableName: String = null): SchemaRDD = {

    val jsonRDD = this.toJSON(sort)

    val schemaRDD = this.spooky.sqlContext.jsonRDD(jsonRDD)

    val validKeyNames = keysSeq
      .filter(key => key.isInstanceOf[Key])
      .map(key => Utils.canonizeColumnName(key.name))
      .filter(name => schemaRDD.schema.fieldNames.contains(name))
    val columns = validKeyNames.map(name => new Column(UnresolvedAttribute(name)))

    val result = schemaRDD.select(columns: _*)

    if (tableName!=null) result.registerTempTable(tableName)

    result
  }

  def toCSV(separator: String = ","): RDD[String] = this.toDataFrame().map {
    _.mkString(separator)
  }

  def toTSV: RDD[String] = this.toCSV("\t")

  /**
   * save each page to a designated directory
   * this is a narrow transformation, use it to save overhead for scheduling
   * support many file systems including but not limited to HDFS, S3 and local HDD
   * @param overwrite if a file with the same name already exist:
   *                  true: overwrite it
   *                  false: append an unique suffix to the new file name
   * @return the same RDD[Page] with file paths carried as metadata
   */
  //always use the same path pattern for filtered pages, if you want pages to be saved with different path, use multiple saveContent with different names
  def savePages(
                 path: Expression[Any],
                 name: Symbol = null,
                 overwrite: Boolean = false
                 ): PageRowRDD = {

    val spooky = this.spooky.broadcast()

    val saved = this.map {

      pageRow =>
        val pathStr = path(pageRow)

        pathStr.foreach {
          str =>
            val strCanon = str
            val page =
              if (name == null || name.name == Const.onlyPageWildcard) pageRow.getOnlyPage
              else pageRow.getPage(name.name)

            page.foreach(_.save(Seq(strCanon.toString), overwrite)(spooky))
        }
        pageRow
    }
    this.copy(store = saved)
  }

  /**
   * same as saveAs
   * but this is an action that will be executed immediately
   * @param overwrite if a file with the same name already exist:
   *                  true: overwrite it
   *                  false: append an unique suffix to the new file name
   * @return an array of file paths
   */
  def dumpPages(
                 path: Expression[String],
                 name: Symbol = null,
                 overwrite: Boolean = false
                 ): Array[ListSet[String]] = this.savePages(path, name, overwrite).flatMap {
    _.pages.map {
      _.saved
    }
  }.collect()

  //  /**
  //   * extract parts of each Page and insert into their respective context
  //   * if a key already exist in old context it will be replaced with the new one.
  //   * @param exprs
  //   * @return new PageRowRDD
  //   */
  def select(exprs: Expression[Any]*): PageRowRDD = {

    val newKeys: Seq[Key] = exprs.map {
      expr =>
        val key = Key(expr.name)
        if(this.keys.contains(key) && !expr.isInstanceOf[PlusExpr[_]]) //can't insert the same key twice
          throw new QueryException(s"Key ${key.name} already exist")
        key
    }

    val result = this.copy(
      store = this.flatMap(_.select(exprs: _*)),
      keys = this.keys ++ newKeys
    )
    result
  }

  private def selectReplace(exprs: Expression[Any]*): PageRowRDD = {

    val newKeys: Seq[Key] = exprs.map {
      expr =>
        val key = Key(expr.name)
        key
    }

    val result = this.copy(
      store = this.flatMap(_.select(exprs: _*)),
      keys = this.keys ++ newKeys
    )
    result
  }


  def overwrite(exprs: Expression[Any]*): PageRowRDD = {

    val newKeys: Seq[Key] = exprs.map {
      expr => Key(expr.name)
    }

    val result = this.copy(
      store = this.flatMap(_.select(exprs: _*)),
      keys = this.keys ++ newKeys
    )
    result
  }

  private def selectTemp(exprs: Expression[Any]*): PageRowRDD = {

    val newKeys: Seq[TempKey] = exprs.map {
      expr =>
        val key = TempKey(expr.name)
        key
    }

    this.copy(
      store = this.flatMap(_.selectTemp(exprs: _*)),
      keys = this.keys ++ newKeys
    )
  }

  def remove(keys: Symbol*): PageRowRDD = {
    val names = keys.map(key => Key(key))
    this.copy(
      store = this.map(_.remove(names: _*)),
      keys = this.keys -- names
    )
  }

  private def clearTemp: PageRowRDD = {
    this.copy(
      store = this.map(_.clearTemp),
      keys = keys -- keys.filter(_.isInstanceOf[TempKey])//circumvent https://issues.scala-lang.org/browse/SI-8985
    )
  }

  def flatten(
               expr: Expression[Any],
               ordinalKey: Symbol = null,
               maxOrdinal: Int = Int.MaxValue,
               left: Boolean = true
               ): PageRowRDD = {
    val selected = this.select(expr)

    val flattened = selected.flatMap(_.flatten(expr.name, ordinalKey, maxOrdinal, left))
    selected.copy(
      store = flattened,
      keys = selected.keys ++ Option(Key.sortKey(ordinalKey))
    )
  }

  private def flattenTemp(
                           expr: Expression[Any],
                           ordinalKey: Symbol = null,
                           maxOrdinal: Int = Int.MaxValue,
                           left: Boolean = true
                           ): PageRowRDD = {
    val selected = this.selectTemp(expr)

    val flattened = selected.flatMap(_.flatten(expr.name, ordinalKey, maxOrdinal, left))
    selected.copy(
      store = flattened,
      keys = selected.keys ++ Option(Key.sortKey(ordinalKey))
    )
  }

  //alias of flatten
  def explode(
               expr: Expression[Any],
               ordinalKey: Symbol = null,
               maxOrdinal: Int = Int.MaxValue,
               left: Boolean = true
               ): PageRowRDD = flatten(expr, ordinalKey, maxOrdinal, left)

  //  /**
  //   * break each page into 'shards', used to extract structured data from tables
  //   * @param selector denotes enclosing elements of each shards
  //   * @param maxOrdinal only the first n elements will be used, default to Const.fetchLimit
  //   * @return RDD[Page], each page will generate several shards
  //   */
  def flatSelect(
                  expr: Expression[Seq[Unstructured]], //avoid confusion
                  ordinalKey: Symbol = null,
                  maxOrdinal: Int = Int.MaxValue,
                  left: Boolean = true
                  )(exprs: Expression[Any]*) ={

    this
      .flattenTemp(expr defaultAs Symbol(Const.defaultJoinKey), ordinalKey, maxOrdinal, left)
      .select(exprs: _*)
  }

  def flattenPages(
                    pattern: Symbol = Symbol(Const.onlyPageWildcard), //TODO: enable it
                    ordinalKey: Symbol = null
                    ): PageRowRDD =
    this.copy(
      store = this.flatMap(_.flattenPages(pattern.name, ordinalKey)),
      keys = this.keys ++ Option(Key.sortKey(ordinalKey))
    )

  def lookup(): RDD[(Trace, PageLike)] = {

    if (this.getStorageLevel == StorageLevel.NONE) this.persist(spooky.conf.defaultStorageLevel)

    this.flatMap(_.pageLikes.map(page => page.uid.backtrace -> page ))
    //TODO: really takes a lot of space, how to eliminate?
    //TODO: unpersist after next action, is it even possible?
  }

  def fetch(
             traces: Set[Trace],
             joinType: JoinType = Const.defaultJoinType,
             flattenPagesPattern: Symbol = '*, //by default, always flatten all pages
             flattenPagesOrdinalKey: Symbol = null,
             numPartitions: Int = spooky.conf.defaultParallelism(this),
             optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
             ): PageRowRDD = {

    val _traces = traces.autoSnapshot

    spooky.broadcast()

    val result = optimizer match {
      case Narrow =>
        _narrowFetch(_traces, joinType, numPartitions)
      case Wide =>
        _wideFetch(_traces, joinType, numPartitions, null)
      case WideLookup =>
        _wideFetch(_traces, joinType, numPartitions, lookup())
      case _ => throw new UnsupportedOperationException(s"${optimizer.getClass.getSimpleName} optimizer is not supported in this query")
    }

    if (flattenPagesPattern != null) result.flattenPages(flattenPagesPattern,flattenPagesOrdinalKey)
    else result
  }

  private def _narrowFetch(
                            _traces: Set[Trace],
                            joinType: JoinType,
                            numPartitions: Int
                            ): PageRowRDD = {

    val spooky = this.spooky

    val resultRows = this
      .coalesce(numPartitions)
      .flatMap(
        row =>
          _traces
            .interpolate(row)
            .flatMap{
            trace =>
              val pages = trace.resolve(spooky)

              row.putPages(pages, joinType)
          }
      )

    this.copy(resultRows)
  }

  private def _wideFetch(
                          _traces: Set[Trace],
                          joinType: JoinType,
                          numPartitions: Int,
                          lookup: RDD[(Trace, PageLike)]
                          ): PageRowRDD = {

    val spooky = this.spooky

    val traceToRows = this.flatMap {
      row =>
        _traces.interpolate(row).map(interpolatedTrace => interpolatedTrace -> row)
    }
      .partitionBy(new HashPartitioner(numPartitions))
      .persist(spooky.conf.defaultStorageLevel)

    val traces = traceToRows.keys.distinct(numPartitions)

    val traceToPages = if (lookup == null) {
      traces.map{
        trace =>
          trace -> trace.resolve(spooky)
      }
    }
    else {
      val backtraceToTraceWithIndex: RDD[(Trace, (Trace, Int))] = traces.flatMap{
        //key not unique, different trace may yield to same backtrace.
        trace =>
          val dryruns = trace.dryrun.zipWithIndex
          if (dryruns.nonEmpty) dryruns.map(tuple => tuple._1 -> (trace, tuple._2))
          else Seq(null.asInstanceOf[Trace] -> (trace, -1))
      }

      val cogrouped = backtraceToTraceWithIndex
        .cogroup(lookup)

      val traceToIndexWithPagesOption: RDD[(Trace, (Int, Option[Seq[PageLike]]))] = cogrouped.flatMap{
        triplet =>
          val backtrace = triplet._1
          val tuple = triplet._2
          val squashedWithIndexes = tuple._1
          if (squashedWithIndexes.isEmpty) {
            Seq()
          }
          else if (backtrace == null) {
            squashedWithIndexes.map{
              squashedWithIndex =>
                squashedWithIndex._1 -> (squashedWithIndex._2, Some(Seq()))
            }
          }
          else {
            val lookupPages = tuple._2
            lookupPages.foreach(_.uid.backtrace.injectFrom(backtrace))

            val latestBatchOption = PageRow.discoverLatestBatch(lookupPages)

            squashedWithIndexes.map{
              squashedWithIndex =>
                squashedWithIndex._1 -> (squashedWithIndex._2, latestBatchOption)
            }
          }
      }

      traceToIndexWithPagesOption.groupByKey(numPartitions).map{ //TODO: great evil! remove it, but not before determining lookup format
        tuple =>
          val trace = tuple._1
          val IndexWithPageOptions = tuple._2.toSeq
          if (IndexWithPageOptions.map(_._2).contains(None)) trace -> trace.resolve(spooky)
          else {
            trace -> IndexWithPageOptions.sortBy(_._1).flatMap(_._2.get)
          }
      }
    }

    val rowsToPages = traceToRows.cogroup(traceToPages).values

    val newRows: RDD[PageRow] = rowsToPages.flatMap{
      tuple =>
        assert(tuple._2.size == 1)
        val pages = tuple._2.head
        tuple._1.flatMap(_.putPages(pages, joinType))
    }

    this.copy(store = newRows)
  }

  def join(
            expr: Expression[Any], //name is discarded
            ordinalKey: Symbol = null, //left & idempotent parameters are missing as they are always set to true
            maxOrdinal: Int = spooky.conf.maxJoinOrdinal
            )(
            traces: Set[Trace],
            joinType: JoinType = Const.defaultJoinType,
            numPartitions: Int = spooky.conf.defaultParallelism(this),
            flattenPagesPattern: Symbol = '*,
            flattenPagesOrdinalKey: Symbol = null,
            optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
            )(
            select: Expression[Any]*
            ): PageRowRDD = {

    this
      .flattenTemp(expr defaultAs Symbol(Const.defaultJoinKey), ordinalKey, maxOrdinal, left = true)
      .fetch(traces, joinType, flattenPagesPattern, flattenPagesOrdinalKey, numPartitions, optimizer)
      .select(select: _*)
  }

  /**
   * results in a new set of Pages by crawling links on old pages
   * old pages that doesn't contain the link will be ignored
   * @param maxOrdinal only the first n links will be used, default to Const.fetchLimit
   * @return RDD[Page]
   */
  def visitJoin(
                 expr: Expression[Any],
                 hasTitle: Boolean = true,
                 ordinalKey: Symbol = null, //left & idempotent parameters are missing as they are always set to true
                 maxOrdinal: Int = spooky.conf.maxJoinOrdinal,
                 joinType: JoinType = Const.defaultJoinType,
                 numPartitions: Int = spooky.conf.defaultParallelism(this),
                 select: Expression[Any] = null,
                 optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
                 ): PageRowRDD =
    this.join(expr, ordinalKey, maxOrdinal)(
      Visit(new GetExpr(Const.defaultJoinKey), hasTitle),
      joinType,
      numPartitions,
      optimizer = optimizer
    )(Option(select).toSeq: _*)

  /**
   * same as join, but avoid launching a browser by using direct http GET (wget) to download new pages
   * much faster and less stressful to both crawling and target server(s)
   * @param maxOrdinal only the first n links will be used, default to Const.fetchLimit
   * @return RDD[Page]
   */
  def wgetJoin(
                expr: Expression[Any],
                hasTitle: Boolean = true,
                ordinalKey: Symbol = null, //left & idempotent parameters are missing as they are always set to true
                maxOrdinal: Int = spooky.conf.maxJoinOrdinal,
                joinType: JoinType = Const.defaultJoinType,
                numPartitions: Int = spooky.conf.defaultParallelism(this),
                select: Expression[Any] = null,
                optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
                ): PageRowRDD =
    this.join(expr, ordinalKey, maxOrdinal)(
      Wget(new GetExpr(Const.defaultJoinKey), hasTitle),
      joinType,
      numPartitions,
      optimizer = optimizer
    )(Option(select).toSeq: _*)

  def explore(
               expr: Expression[Any],
               depthKey: Symbol = null,
               maxDepth: Int = spooky.conf.maxExploreDepth,
               ordinalKey: Symbol = null,
               maxOrdinal: Int = spooky.conf.maxJoinOrdinal,
               checkpointInterval: Int = spooky.conf.checkpointInterval
               )(
               traces: Set[Trace],
               numPartitions: Int = spooky.conf.defaultParallelism(this),
               flattenPagesPattern: Symbol = '*,
               flattenPagesOrdinalKey: Symbol = null,
               optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
               )(
               select: Expression[Any]*
               ): PageRowRDD = {

    val _traces = traces.autoSnapshot

    spooky.broadcast()

    val result = optimizer match {
      case Narrow =>
        _narrowExplore(expr, depthKey, maxDepth, ordinalKey, maxOrdinal, checkpointInterval)(_traces, numPartitions, flattenPagesPattern, flattenPagesOrdinalKey)(select: _*)
      case Wide =>
        _wideExplore(expr, depthKey, maxDepth, ordinalKey, maxOrdinal, checkpointInterval, null)(_traces, numPartitions, flattenPagesPattern, flattenPagesOrdinalKey)(select: _*)
      case WideLookup =>
        _wideExplore(expr, depthKey, maxDepth, ordinalKey, maxOrdinal, checkpointInterval, this.lookup())(_traces, numPartitions, flattenPagesPattern, flattenPagesOrdinalKey)(select: _*)
      case _ => throw new UnsupportedOperationException(s"${optimizer.getClass.getSimpleName} optimizer is not supported in this query")
    }

    result
  }

  //this is a single-threaded explore, of which implementation is similar to good old pagination.
  //may fetch same page twice or more if pages of this can reach each others. TODO: Deduplicate happens between stages
  private def _narrowExplore(
                              expr: Expression[Any],
                              depthKey: Symbol,
                              maxDepth: Int,
                              ordinalKey: Symbol,
                              maxOrdinal: Int,
                              checkpointInterval: Int
                              )(
                              _traces: Set[Trace],
                              numPartitions: Int,
                              flattenPagesPattern: Symbol,
                              flattenPagesOrdinalKey: Symbol
                              )(
                              select: Expression[Any]*
                              ): PageRowRDD = {

    val spooky = this.spooky

    val _expr = expr defaultAs Symbol(Const.defaultJoinKey)

    var depthFromExclusive = 0

    if (this.getStorageLevel == StorageLevel.NONE) this.persist(spooky.conf.defaultStorageLevel)
    if (this.context.getCheckpointDir.isEmpty) this.context.setCheckpointDir(spooky.conf.dirs.checkpoint)

    val firstResultRDD = this
      .coalesce(numPartitions) //TODO: simplify

    val firstStageRDD = firstResultRDD
      .map {
      row =>
        val seeds = Seq(row)
        val dryruns = row
          .pageLikes
          .map(_.uid)
          .groupBy(_.backtrace)
          .filter{
          tuple =>
            tuple._2.size == tuple._2.head.blockTotal //I hope this is sufficient condition
        }
          .keys.toSet

        ExploreStage(seeds, dryruns = Set(dryruns))
    }

    val resultRDDs = ArrayBuffer[RDD[PageRow]](
      firstResultRDD
        .clearTemp
        .select(Option(depthKey).map(key => Literal(0) ~ key).toSeq: _*)
    )

    val resultKeys = this.keys ++ Seq(TempKey(_expr.name), Key.sortKey(depthKey), Key.sortKey(ordinalKey), Key.sortKey(flattenPagesOrdinalKey)).flatMap(Option(_))

    //    val resultSortKeysSeq: Seq[Key] = resultKeys.toSeq.reverse.flatMap{
    //      case k: Key with SortKey => Some(k)
    //      case _ => None
    //    }

    var stageRDD = firstStageRDD
    while(true) {

      val _depthFromExclusive = depthFromExclusive //var in closure being shipped to workers usually end up miserably (not synched properly)
      val depthToInclusive = Math.min(_depthFromExclusive + checkpointInterval, maxDepth)

      //      assert(_depthFromExclusive < depthToInclusive, _depthFromExclusive.toString+":"+ depthToInclusive)

      val batchExeRDD = stageRDD.map {
        stage =>
          PageRow.localExplore(
            stage,
            spooky
          )(
              _expr,
              depthKey,
              _depthFromExclusive,
              depthToInclusive,
              ordinalKey,
              maxOrdinal
            )(
              _traces,
              flattenPagesPattern,
              flattenPagesOrdinalKey
            )
      }

      batchExeRDD.checkpointNow()

      stageRDD = batchExeRDD.map(_._2).filter(_.hasMore) //TODO: repartition to balance?

      val totalRDD = batchExeRDD.flatMap(_._1)
      resultRDDs += totalRDD

      val count = stageRDD.count()
      LoggerFactory.getLogger(this.getClass).info(s"$count segment(s) have uncrawled seed(s) after $depthToInclusive iteration(s)")
      depthFromExclusive = depthToInclusive

      if (count == 0 || depthToInclusive >= maxDepth) return result
    }

    def result: PageRowRDD = {

      val resultSelf = new UnionRDD(this.sparkContext, resultRDDs).coalesce(numPartitions) //TODO: not an 'official' API, and not efficient
      val result = this.copy(store = resultSelf, keys = resultKeys)
      result.select(select: _*)
    }

    result
  }

  //has 2 outputs: 1 is self merge another by Signature, 2 is self distinct not covered by another
  //remember base MUST HAVE a hash partitioner!!!
  private def mergeAndSubtractBySignature(
                                           base: RDD[(Signature, PageRow)],
                                           needCheckpointing: Boolean,
                                           ordinalKey: Symbol
                                           )(
                                           select: Expression[Any]*
                                           ): (RDD[(Signature, PageRow)], PageRowRDD) = {

    val self = this.keyBy(_.signature)

    val cogrouped = base.cogroup(self)

    val mixed = cogrouped.mapValues{
      tuple =>
        if (tuple._1.nonEmpty) {
          val oldRowOption = PageRow.selectFirstRow(tuple._1, ordinalKey)
          oldRowOption.head -> None
        }
        else {
          val newRowOption = PageRow.selectFirstRow(tuple._2, ordinalKey)
          val newRowSelected = newRowOption.get.select(select: _*).get

          newRowSelected -> newRowOption
        }
    }

    if (needCheckpointing) mixed.checkpointNow()
    else mixed.persist(spooky.conf.defaultStorageLevel)

    val merged = mixed.mapValues(_._1)
    val newSeeds = mixed.values.flatMap(_._2)

    merged -> this.copy(store = newSeeds)
  }

  //recursive join and union! applicable to many situations like (wide) pagination and deep crawling
  private def _wideExplore(
                            expr: Expression[Any],
                            depthKey: Symbol,
                            maxDepth: Int,
                            ordinalKey: Symbol,
                            maxOrdinal: Int,
                            checkpointInterval: Int,
                            lookup: RDD[(Trace, PageLike)]
                            )(
                            _traces: Set[Trace],
                            numPartitions: Int,
                            flattenPagesPattern: Symbol,
                            flattenPagesOrdinalKey: Symbol
                            )(
                            select: Expression[Any]*
                            ): PageRowRDD = {

    val spooky = this.spooky

    if (this.getStorageLevel == StorageLevel.NONE) this.persist(spooky.conf.defaultStorageLevel)
    if (this.context.getCheckpointDir.isEmpty) this.context.setCheckpointDir(spooky.conf.dirs.checkpoint)

    var newSeeds = this

    var accumulated: RDD[(Signature, PageRow)] =
      this
        .clearTemp
        .select(Option(depthKey).map(key => Literal(0) ~ key).toSeq: _*)
        .keyBy(_.signature)
        .partitionBy(new HashPartitioner(numPartitions))

    val _expr = expr defaultAs Symbol(Const.defaultJoinKey)

    val resultKeys = this.keys ++ Seq(TempKey(_expr.name), Key.sortKey(depthKey), Key.sortKey(ordinalKey), Key.sortKey(flattenPagesOrdinalKey)).flatMap(Option(_))

    var lookupAccumulated = Option(lookup).map{
      _.partitionBy(new HashPartitioner(numPartitions))
    }.orNull

    for (depth <- 1 to maxDepth) {
      val newPages = newSeeds
        .flattenTemp(_expr, ordinalKey, maxOrdinal, left = true)
        ._wideFetch(_traces, Inner, numPartitions, lookupAccumulated)

      if (lookupAccumulated != null) {
        val newLookups = newPages.lookup()

        lookupAccumulated = lookupAccumulated.union(newLookups)

        if (depth % checkpointInterval == 0) {
          lookupAccumulated.checkpointNow()
        }
      }

      val joined = newPages
        .flattenPages(flattenPagesPattern, flattenPagesOrdinalKey)

      val tuple = joined.mergeAndSubtractBySignature(accumulated, depth % checkpointInterval == 0, ordinalKey)(
        Option(depthKey).map(k => Literal(depth) ~ k).toSeq: _*
      )
      accumulated = tuple._1
      newSeeds = tuple._2

      val newRowsSize = newSeeds.count()
      LoggerFactory.getLogger(this.getClass).info(s"found $newRowsSize new row(s) after $depth iterations")

      if (newRowsSize == 0) return result
    }

    def result = {
      val r0 = this.copy(store = accumulated.values, keys = resultKeys)
      val res = r0.select(select: _*)
      res
    }

    result
  }

  def visitExplore(
                    expr: Expression[Any],
                    hasTitle: Boolean = true,
                    depthKey: Symbol = null,
                    maxDepth: Int = spooky.conf.maxExploreDepth,
                    ordinalKey: Symbol = null,
                    maxOrdinal: Int = spooky.conf.maxJoinOrdinal,
                    checkpointInterval: Int = spooky.conf.checkpointInterval,
                    numPartitions: Int = spooky.conf.defaultParallelism(this),
                    select: Expression[Any] = null,
                    optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
                    ): PageRowRDD =
    explore(expr, depthKey, maxDepth, ordinalKey, maxOrdinal, checkpointInterval)(
      Visit(new GetExpr(Const.defaultJoinKey), hasTitle),
      numPartitions,
      optimizer = optimizer
    )(Option(select).toSeq: _*)

  def wgetExplore(
                   expr: Expression[Any],
                   hasTitle: Boolean = true,
                   depthKey: Symbol = null,
                   maxDepth: Int = spooky.conf.maxExploreDepth,
                   ordinalKey: Symbol = null,
                   maxOrdinal: Int = spooky.conf.maxJoinOrdinal,
                   checkpointInterval: Int = spooky.conf.checkpointInterval,
                   numPartitions: Int = spooky.conf.defaultParallelism(this),
                   select: Expression[Any] = null,
                   optimizer: QueryOptimizer = spooky.conf.defaultQueryOptimizer
                   ): PageRowRDD =
    explore(expr, depthKey, maxDepth, ordinalKey, maxOrdinal, checkpointInterval)(
      Wget(new GetExpr(Const.defaultJoinKey), hasTitle),
      numPartitions,
      optimizer = optimizer
    )(Option(select).toSeq: _*)
}