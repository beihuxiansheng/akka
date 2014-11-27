/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.collection.immutable
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.language.existentials
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Flow
import akka.stream.stage._

/**
 * Provides operations needed to implement the `timed` DSL
 */
private[akka] trait TimedOps {

  import Timed._

  /**
   * INTERNAL API
   *
   * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O](flow: Source[I], measuredOps: Source[I] ⇒ Source[O], onComplete: FiniteDuration ⇒ Unit): Source[O] = {
    val ctx = new TimedFlowContext

    val startWithTime = flow.transform("startTimed", () ⇒ new StartTimedFlow(ctx))
    val userFlow = measuredOps(startWithTime)
    userFlow.transform("stopTimed", () ⇒ new StopTimed(ctx, onComplete))
  }

  /**
   * INTERNAL API
   *
   * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O, Out](flow: Flow[I, O], measuredOps: Flow[I, O] ⇒ Flow[O, Out], onComplete: FiniteDuration ⇒ Unit): Flow[O, Out] = {
    // todo is there any other way to provide this for Flow, without duplicating impl? (they don't share any super-type)
    val ctx = new TimedFlowContext

    val startWithTime: Flow[I, O] = flow.transform("startTimed", () ⇒ new StartTimedFlow(ctx))
    val userFlow: Flow[O, Out] = measuredOps(startWithTime)
    userFlow.transform("stopTimed", () ⇒ new StopTimed(ctx, onComplete))
  }

}

/**
 * INTERNAL API
 *
 * Provides operations needed to implement the `timedIntervalBetween` DSL
 */
private[akka] trait TimedIntervalBetweenOps {

  import Timed._

  /**
   * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[O](flow: Source[O], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Source[O] = {
    flow.transform("timedInterval", () ⇒ new TimedIntervalTransformer[O](matching, onInterval))
  }

  /**
   * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[I, O](flow: Flow[I, O], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I, O] = {
    // todo is there any other way to provide this for Flow / Duct, without duplicating impl? (they don't share any super-type)
    flow.transform("timedInterval", () ⇒ new TimedIntervalTransformer[O](matching, onInterval))
  }
}

object Timed extends TimedOps with TimedIntervalBetweenOps {

  // todo needs java DSL

  final class TimedFlowContext {
    import scala.concurrent.duration._

    private val _start = new AtomicLong
    private val _stop = new AtomicLong

    def start(): Unit = {
      _start.compareAndSet(0, System.nanoTime())
    }

    def stop(): FiniteDuration = {
      _stop.compareAndSet(0, System.nanoTime())
      compareStartAndStop()
    }

    private def compareStartAndStop(): FiniteDuration = {
      val stp = _stop.get
      if (stp <= 0) Duration.Zero
      else (stp - _start.get).nanos
    }
  }

  final class StartTimedFlow[T](timedContext: TimedFlowContext) extends PushStage[T, T] {
    private var started = false

    override def onPush(elem: T, ctx: Context[T]): Directive = {
      if (!started) {
        timedContext.start()
        started = true
      }
      ctx.push(elem)
    }
  }

  final class StopTimed[T](timedContext: TimedFlowContext, _onComplete: FiniteDuration ⇒ Unit) extends PushStage[T, T] {

    override def onPush(elem: T, ctx: Context[T]): Directive = ctx.push(elem)

    override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
      stopTime()
      ctx.fail(cause)
    }
    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
      stopTime()
      ctx.finish()
    }
    private def stopTime() {
      val d = timedContext.stop()
      _onComplete(d)
    }

  }

  final class TimedIntervalTransformer[T](matching: T ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit) extends PushStage[T, T] {
    private var prevNanos = 0L
    private var matched = 0L

    override def onPush(elem: T, ctx: Context[T]): Directive = {
      if (matching(elem)) {
        val d = updateInterval(elem)

        if (matched > 1)
          onInterval(d)
      }
      ctx.push(elem)
    }

    private def updateInterval(in: T): FiniteDuration = {
      matched += 1
      val nowNanos = System.nanoTime()
      val d = nowNanos - prevNanos
      prevNanos = nowNanos
      d.nanoseconds
    }
  }

}