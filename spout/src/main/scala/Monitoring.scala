package intelmedia.ws.monitoring

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ExecutorService, ScheduledExecutorService, ThreadFactory}
import scala.concurrent.duration._
import scala.language.higherKinds
import scalaz.concurrent.{Actor,Strategy,Task}
import scalaz.Nondeterminism
import scalaz.stream._
import scalaz.stream.async
import scalaz.{~>, Monad}

/**
 * A hub for publishing and subscribing to streams
 * of values.
 */
trait Monitoring {
  import Monitoring._

  /**
   * Create a new topic with the given label and units,
   * using a stream transducer to
   */
  def topic[I, O:Reportable](
    label: String, units: Units[O])(
    buf: Process1[(I,Duration),O]): (Key[O], I => Unit)

  /**
   * Return the continuously updated signal of the current value
   * for the given `Key`. Use `get(k).discrete` to get the
   * discrete stream of values for this key, updated only
   * when new values are produced.
   */
  def get[O](k: Key[O]): async.immutable.Signal[O]

  /**
   * Return the units associated with the given key.
   */
  def units[O](k: Key[O]): Units[O]

  /**
   * Return a witness for the type of the given key.
   */
  def typeOf[O](k: Key[O]): Reportable[O]

  /**
   * Publish a metric with the given label on every tick of `events`.
   * See `Events` for various combinators for building up possible
   * arguments to pass here (periodically, when one or more keys
   * change, etc).
   */
  def publish[O:Reportable](
      label: String, units: Units[O])(events: Process[Task,Unit])(f: Metric[O]): Key[O] = {
    // `trans` is a polymorphic fn from `Key` to `Task`, picks out
    // latest value for that `Key`
    val trans = new (Key ~> Task) {
      def apply[A](k: Key[A]): Task[A] = latest(k)
    }
    // Invoke Metric interpreter, giving it function from Key to Task
    val refresh: Task[O] = f.run(trans)
    // Whenever `event` generates a new value, refresh the signal
    val proc: Process[Task, O] = events.flatMap(_ => Process.eval(refresh))
    // And finally republish these values to a new topic
    val (k, snk) = topic[O,O](label, units)(Buffers.ignoreTime(process1.id))
    proc.map(snk).run.runAsync(_ => ()) // nonblocking
    k
  }

  // gak, no typechecking - we should try to fail fast
  // server should publish type as well as units
  // switch away from using view bounds, Reportable[O]
  // has a type and

  // could have mirror(events)(url, prefix), which is polling
  // rather than pushing

  def mirror[O:Reportable](url: String, prefix: String)(
      implicit S: ExecutorService = Monitoring.defaultPool): Task[Key[O]] =
    SSE.readEvent(url, prefix).map { case (key, stream) =>
      // todo: preserve the old key and units
      val (k, snk) = topic[O,O](prefix, Units.None)(
        Buffers.ignoreTime(process1.id)
      )
      // we want this process to be asynchronous
      Process.eval { Task(()) } . flatMap { (u: Unit) =>
        stream.evalMap { case (_, value) =>
          Task { snk(value.asInstanceOf[O]) }
        }
      }.run.runAsync(_ => ())
      key.asInstanceOf[Key[O]]
    }

  // def mirrorAll(url: String): Task[Unit]

  /** Return the elapsed time since this instance was started. */
  def elapsed: Duration

  /** Return the most recent value for a given key. */
  def latest[O](k: Key[O]): Task[O] =
    get(k).continuous.once.runLast.map(_.get)

  /** The time-varying set of keys. */
  def keys: async.immutable.Signal[List[Key[Any]]]

  /** The infinite discrete stream of unique keys, as they are added. */
  def distinctKeys: Process[Task, Key[Any]] =
    keys.discrete.flatMap(Process.emitAll).pipe(Buffers.distinct)

  /** Create a new topic with the given label and discard the key. */
  def topic_[I, O:Reportable](
    label: String, units: Units[O])(
    buf: Process1[(I,Duration),O]): I => Unit = topic(label, units)(buf)._2

  def keysByLabel(label: String): Process[Task, List[Key[Any]]] =
    keys.continuous.map(_.filter(_.label == label))
}

object Monitoring {

  private def daemonThreads(name: String) = new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }

  val defaultPool: ExecutorService =
    Executors.newFixedThreadPool(8, daemonThreads("monitoring-thread"))

  val serverPool: ExecutorService =
    Executors.newCachedThreadPool(daemonThreads("monitoring-http-server"))

  val schedulingPool: ScheduledExecutorService =
    Executors.newScheduledThreadPool(4, daemonThreads("monitoring-scheduled-tasks"))

  val default: Monitoring = instance(defaultPool)

  def instance(implicit ES: ExecutorService = defaultPool): Monitoring = {
    import async.immutable.Signal
    import scala.collection.concurrent.TrieMap
    val t0 = System.nanoTime
    val S = Strategy.Executor(ES)
    val P = Process
    val keys_ = async.signal[List[Key[Any]]](S)
    keys_.value.set(List())

    case class Topic[I,O](
      publish: ((I,Duration), Option[O] => Unit) => Unit,
      current: async.immutable.Signal[O]
    )
    val topics = new TrieMap[Key[Any], Topic[Any,Any]]()
    val us = new TrieMap[Key[Any], (Reportable[Any], Units[Any])]()

    def eraseTopic[I,O](t: Topic[I,O]): Topic[Any,Any] = t.asInstanceOf[Topic[Any,Any]]

    new Monitoring {
      def keys = keys_

      def topic[I, O:Reportable](
          label: String, units: Units[O])(
          buf: Process1[(I,Duration),O]): (Key[O], I => Unit) = {
        val (pub, v) = bufferedSignal(buf)(ES)
        val k = Key[O](label)
        topics += (k -> eraseTopic(Topic(pub, v)))
        val t = (implicitly[Reportable[O]], units)
        us += (k -> t)
        keys_.value.modify(k :: _)
        (k, (i: I) => {
          val elapsed = Duration.fromNanos(System.nanoTime - t0)
          pub(i -> elapsed, _ => {})
        })
      }

      def get[O](k: Key[O]): Signal[O] =
        topics.get(k).map(_.current.asInstanceOf[Signal[O]])
                     .getOrElse(sys.error("key not found: " + k))

      def units[O](k: Key[O]): Units[O] =
        us.get(k).map(_._2.asInstanceOf[Units[O]])
                 .getOrElse(sys.error("key not found: " + k))

      def typeOf[O](k: Key[O]): Reportable[O] =
        us.get(k).map(_._1.asInstanceOf[Reportable[O]])
                 .getOrElse(sys.error("key not found: " + k))

      def elapsed: Duration = Duration.fromNanos(System.nanoTime - t0)
    }
  }

  /**
   * Return a discrete stream of updates to all keys
   * matching the given prefix. Note that:
   *
   *   a) There is no queueing of producer updates,
   *      so a 'slow' consumer can miss updates.
   *   b) The returned stream is 'use-once' and will
   *      halt the producer when completed. Just
   *      resubscribe if you need a fresh stream.
   */
  def subscribe(M: Monitoring)(prefix: String, log: String => Unit = println)(
  implicit ES: ExecutorService = serverPool):
      Process[Task, Datapoint[Any]] =
    Process.suspend { // don't actually do anything until we have a consumer
      val S = Strategy.Executor(ES)
      val out = scalaz.stream.async.signal[Datapoint[Any]](S)
      val alive = scalaz.stream.async.signal[Boolean](S)
      val heartbeat = alive.continuous.takeWhile(identity)
      alive.value.set(true)
      S { // in the background, populate the 'out' `Signal`
        alive.discrete.map(!_).wye(M.distinctKeys)(wye.interrupt)
        .filter(_.matches(prefix))
        .map { k =>
          // asynchronously set the output
          S { M.get(k).discrete
               .map(v => out.value.set(Datapoint(k, M.typeOf(k), M.units(k), v)))
               .zip(heartbeat)
               .onComplete { Process.eval_{ Task.delay(log("unsubscribing: " + k))} }
               .run.run
            }
        }.run.run
        log("killed producer for prefix: " + prefix)
      }
      // kill the producers when the consumer completes
      out.discrete onComplete {
        Process.eval_ { Task.delay {
          log("killing producers for prefix: " + prefix)
          out.close
          alive.value.set(false)
        }}
      }
    }

  /**
   * Obtain the latest values for all active metrics.
   */
  def snapshot(M: Monitoring)(implicit ES: ExecutorService = defaultPool):
    Task[collection.Map[Key[Any], Datapoint[Any]]] = {
    val m = collection.concurrent.TrieMap[Key[Any], Datapoint[Any]]()
    val S = Strategy.Executor(ES)
    for {
      ks <- M.keys.continuous.once.runLastOr(List())
      t <- Nondeterminism[Task].gatherUnordered {
        ks.map(k => M.get(k).continuous.once.runLast.map(
          _.map(v => k -> Datapoint(k, M.typeOf(k), M.units(k), v))
        ).timed(100L).attempt.map(_.toOption))
      }
      _ <- Task { t.flatten.flatten.foreach(m += _) }
    } yield m
  }

  /**
   * Send values through a `Process1[I,O]` to a `Signal[O]`, which will
   * always be equal to the most recent value produced by `buf`. Sending
   * `None` to the returned `Option[I] => Unit` closes the `Signal`.
   * Sending `Some(i)` updates the value of the `Signal`, after passing
   * `i` through `buf`.
   */
  private[monitoring] def bufferedSignal[I,O](
      buf: Process1[I,O])(
      implicit ES: ExecutorService = defaultPool):
      ((I, Option[O] => Unit) => Unit, async.immutable.Signal[O]) = {
    val signal = async.signal[O](Strategy.Executor(ES))
    var cur = buf.unemit match {
      case (h, t) if h.nonEmpty => signal.value.set(h.last); t
      case (h, t) => t
    }
    val hub = Actor.actor[(I, Option[O] => Unit)] { case (i,done) =>
      val (h, t) = process1.feed1(i)(cur).unemit
      if (h.nonEmpty) {
        val out = Some(h.last)
        signal.value.compareAndSet(_ => out, _ => done(out))
      }
      else done(None)
      cur = t
      cur match {
        case Process.Halt(e) => signal.value.fail(e)
        case _ => ()
      }
    } (Strategy.Sequential)
    ((i: I, done: Option[O] => Unit) => hub ! (i -> done), signal)
  }

}

