package com.twitter.finagle.util

import com.twitter.conversions.DurationOps._
import com.twitter.util.{Closable, Duration, Future, Time, Timer, MockTimer}
import org.HdrHistogram.{Histogram, Recorder}
import scala.collection.mutable

object WindowedPercentileHistogram {
  // Based on testing, a window of 30 seconds and 3 buckets tracked request
  // latency well and had no noticeable performance difference vs. a greater number of
  // buckets.
  private[finagle] val DefaultNumBuckets: Int = 3
  private[finagle] val DefaultBucketSize: Duration = 10.seconds
  private[finagle] val DefaultLowestDiscernibleValue: Int = 1
  private[finagle] val DefaultHighestTrackableValue: Int = 2.seconds.inMillis.toInt

  // Number of significant decimal digits to which the histogram will maintain value resolution
  // and separation. A value of 3 means +/- 1 unit at 1000.
  private val HdrPrecision = 3

  private def newEmptyHistogram(
    lowestDiscernibleValue: Int,
    highestTrackableValue: Int
  ): Histogram = {
    new Histogram(lowestDiscernibleValue, highestTrackableValue, HdrPrecision)
  }

  private val addHistograms: (Histogram, Histogram) => Histogram =
    (h1: Histogram, h2: Histogram) => {
      h1.add(h2)
      h1
    }
}

/**
 * Sliding window of `numBuckets` histograms. Each bucket covers a `bucketSize` interval and must
 * be full before being counted in `percentile`.
 */
class WindowedPercentileHistogram(
  numBuckets: Int,
  bucketSize: Duration,
  lowestDiscernibleValue: Int,
  val highestTrackableValue: Int,
  timer: Timer)
    extends Closable {
  import WindowedPercentileHistogram._

  def this(numBuckets: Int, bucketSize: Duration, timer: Timer) =
    this(
      numBuckets,
      bucketSize,
      WindowedPercentileHistogram.DefaultLowestDiscernibleValue,
      WindowedPercentileHistogram.DefaultHighestTrackableValue,
      timer
    )

  def this(timer: Timer) =
    this(
      WindowedPercentileHistogram.DefaultNumBuckets,
      WindowedPercentileHistogram.DefaultBucketSize,
      WindowedPercentileHistogram.DefaultLowestDiscernibleValue,
      WindowedPercentileHistogram.DefaultHighestTrackableValue,
      timer
    )

  // Provides stable interval Histogram samples from recorded values without
  // stalling recording. `recordValue` can be called concurrently.
  private[this] val recorder = new Recorder(HdrPrecision)

  // Circular buffer of [[Histogram]]s. `recorder.getIntervalHistogram` optionally takes a
  // [[Histogram]] to re-use, but it must have been produced from a previous call to
  // `recorder.getIntervalHistogram`, so we populate the buffer as such.
  // Writes/reads only occur in the synchronized `flushCurrentBucket` method.
  private[this] val histograms =
    mutable.Seq.fill[Histogram](numBuckets)(recorder.getIntervalHistogram)

  @volatile private[this] var currentSnapshot: Histogram = null

  // Current index in the buffer to flush to. Writes/reads only occur in the synchronized
  // `flushCurrentBucket` method.
  private[this] var pos: Int = 0

  // exposed for testing.
  private[util] def flushCurrentBucket(): Unit = synchronized {
    histograms(pos) = recorder.getIntervalHistogram(histograms(pos))
    currentSnapshot = histograms.fold(
      newEmptyHistogram(lowestDiscernibleValue, highestTrackableValue)
    )(addHistograms)
    pos = (pos + 1) % numBuckets
  }

  private[this] val flushCurrentBucketTask = timer.schedule(bucketSize)(flushCurrentBucket)

  /**
   * Add a value to the histogram.
   * @param value Value to add. Values larger than [[highestTrackableValue]] will be added
   *              as [[highestTrackableValue]].
   */
  def add(value: Int): Unit = {
    recorder.recordValue(Math.min(value, highestTrackableValue))
  }

  /**
   * Retrieve a percentile from the histogram.
   * @param percentile Percentile to retrieve. Must be be [0.0, 1.0]
   */
  def percentile(percentile: Double): Int = {
    if (percentile < 0 || percentile > 1) {
      throw new IllegalArgumentException(s"Percentile must be [0.0, 1.0]. Was: $percentile")
    }

    if (currentSnapshot != null)
      currentSnapshot.getValueAtPercentile(percentile * 100).toInt
    else
      0
  }

  override def close(deadline: Time): Future[Unit] = {
    flushCurrentBucketTask.cancel()
    Future.Done
  }
}

/**
 * Just for testing.  Stores only the last added value
 */
private[finagle] class MockWindowedPercentileHistogram(timer: MockTimer)
    extends WindowedPercentileHistogram(0, Duration.Top, 1, 2000, timer) {

  def this() = this(new MockTimer())

  private[this] var _value: Int = 0

  var closed = false

  override def add(value: Int): Unit =
    _value = value

  override def percentile(percentile: Double): Int =
    _value

  override def close(deadline: Time): Future[Unit] = {
    closed = true
    Future.Done
  }
}
