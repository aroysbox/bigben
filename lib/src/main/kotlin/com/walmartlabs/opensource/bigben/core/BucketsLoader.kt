package com.walmartlabs.opensource.bigben.core

import com.google.common.util.concurrent.ListenableScheduledFuture
import com.walmartlabs.opensource.bigben.core.BucketManager.Companion.scheduler
import com.walmartlabs.opensource.bigben.entities.Bucket
import com.walmartlabs.opensource.bigben.extns.done
import com.walmartlabs.opensource.bigben.extns.fetch
import com.walmartlabs.opensource.bigben.extns.logger
import com.walmartlabs.opensource.bigben.extns.rootCause
import com.walmartlabs.opensource.bigben.utils.Props
import com.walmartlabs.opensource.bigben.utils.TaskExecutor
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

/**
 * Created by smalik3 on 2/22/18
 */
class BucketsLoader(private val lookbackRange: Int, private val fetchSize: Int, private val predicate: Predicate<ZonedDateTime>,
                    private val bucketWidth: Int, val bucketId: ZonedDateTime, val consumer: (Bucket) -> Unit) : Runnable {

    companion object {
        private val l = logger<BucketsLoader>()
    }

    private val waitInterval = Props.int("buckets.background.load.wait.interval.seconds", 15)
    private val runningJob = AtomicReference<ListenableScheduledFuture<*>>()
    private val taskExecutor = TaskExecutor(setOf(Exception::class.java))

    override fun run() {
        l.info("starting the background load of buckets at a rate of {} buckets per {} seconds until {} buckets are loaded", fetchSize, waitInterval, lookbackRange)
        runningJob.set(scheduler.schedule({ load(0) }, 0, SECONDS))
    }

    private fun load(fromIndex: Int) {
        if (fromIndex >= lookbackRange) {
            if (l.isInfoEnabled) l.info("lookback range reached, no more buckets will be loaded in background")
        } else {
            if (l.isInfoEnabled) l.info("initiating background load of buckets from index: {}", fromIndex)
            val currentBucketIndex = AtomicReference<Int>()
            val atLeastOne = AtomicBoolean()
            (1..fetchSize).forEach {
                val bucketIndex = fromIndex + it
                if (bucketIndex <= lookbackRange) {
                    currentBucketIndex.set(bucketIndex)
                    val bId = bucketId.minusSeconds((bucketIndex * bucketWidth).toLong())
                    if (!predicate.test(bId)) {
                        atLeastOne.set(true)
                        l.info("loading bucket: {}, failures will be retried {} times, every {} seconds", bId, lookbackRange - bucketIndex + 1, bucketWidth)
                        taskExecutor.async("bucket-load:" + bId, lookbackRange - bucketIndex + 1, bucketWidth, 1) { fetch<Bucket> { it.id = bId } }
                                .done({ l.error("error in loading bucket {}, system is giving up", bId, it.rootCause()) }) {
                                    if (l.isDebugEnabled)
                                        l.info("bucket {} loaded successfully", bId)
                                    consumer(it ?: BucketManager.Companion.EmptyBucket(bucketId))
                                }
                    } else {
                        if (l.isDebugEnabled) l.debug("bucket {} already loaded, skipping...", bId)
                    }
                } else {
                    if (l.isInfoEnabled) l.info("no more buckets to load, look back range reached")
                }
            }
            runningJob.set(scheduler.schedule({ load(currentBucketIndex.get()) }, (if (!atLeastOne.get()) 0 else waitInterval).toLong(), SECONDS))
        }
    }
}