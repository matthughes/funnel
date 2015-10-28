package funnel
package chemist

import java.net.URI
import journal.Logger
import scalaz.{\/,\/-}
import scala.concurrent.duration._
import scalaz.concurrent.{Task,Strategy}
import scalaz.stream.async.mutable.{Queue,Signal}
import scalaz.stream.{Process,Process1,Sink,time,channel,wye,sink,async}

object Pipeline {
  import Chemist.{Context,Flow}
  import Sharding.Distribution
  import PlatformEvent._

  private[this] val log = Logger[Pipeline.type]

  /**
   * periodically wake up and call the platform discovery system. doing this
   * ensures that we capture any outliers, despite having the more event-based
   * platform lifecycle stream (which could periodically fail).
   */
  def discover(dsc: Discovery, interval: Duration): Flow[Target] = {
    (Process.emit(Duration.Zero) ++ time.awakeEvery(interval)(Strategy.Executor(Chemist.serverPool), Chemist.schedulingPool)).flatMap { _ =>
      val task: Task[Seq[Context[Target]]] = for {
        a <- dsc.listActiveFlasks
        b <- dsc.listTargets.map(_.map(_._2).flatten)
        c  = a.foldLeft(Distribution.empty){ (x,y) => x.insert(y, Set.empty[Target]) }
      } yield b.map(Context(c,_))

      Process.eval(task).flatMap(Process.emitAll)
    }
  }

  /**
   * basically just lift a given A into a Context A... perhaps this would be
   * better on the Context compantion object?
   */
  def contextualise[A](a: A): Context[A] =
    Context(Distribution.empty, a)

  /**
   * grab the existing work from the shards, and update the distribution;
   * our view of the world as it is right now (stale and immedietly non-authoritive)
   */
  def collect(http: dispatch.Http)(d: Distribution): Task[Distribution] =
    Flask.gatherAssignedTargets(Sharding.shards(d))(http)

  object handle {
    /**
     * distribution is the specific work that needs to take place, represented as a distribution
     */
    def newTarget(target: Target, sharder: Sharder)(d: Distribution): Distribution =
      sharder.distribution(Set(target))(d)._2 // drop the seq, as its not needed

    /**
     * in the event more capacity becomes avalible, rebalence the cluster to take
     * best advantage of that new capacity using the specified sharder to
     * redistribute the work. This function is soley responsible for orchestrating
     * the inputs/outputs of the sharder, and the actual imlpementaiton logic of
     * what to shard where is entirely encapsulated in the `Sharder`.
     */
    def newFlask(flask: Flask, shd: Sharder)(old: Distribution): (Distribution, Redistribute) = {
      val flasks: IndexedSeq[Flask] = Sharding.shards(old)
      val targets: Set[Target] = Sharding.targets(old)
      val empty: Distribution = flasks.foldLeft(Distribution.empty)(
        (a,b) => a.insert(b, Set.empty)).insert(flask, Set.empty)

      val proposed: Distribution = shd.distribution(targets)(empty)._2

      val r1 = proposed.fold(Redistribute.empty){ (f, t, r) =>
        if(f.id == flask.id) r.update(f, stopping = Set.empty, starting = t)
        else {
          // targets previously assigned to this flask
          val previous = old.lookup(f).getOrElse(Set.empty[Target])
          // of those targets, see what work is already assigned to
          // the very same shard, and ignore it as its already good
          // where it is. any work that didnt match (i.e. wasn't on
          // this shard in the new state should be stopped for this
          // particular shard).
          val (ignore,_) = t.partition(t => previous.contains(t))
          // produce the redistribution for this flask
          r.update(f, previous -- ignore, t -- ignore)
        }
      }
      (proposed, r1)
    }
  }

  /**
   * a simple transducer that converts `PlatformEvent` into a `Plan` so that
   * the stream can be fed to whatever sink has been wired to this process.
   * this function should only ever be indicating what the intended actions
   * are, not actually doing any effectful I/O itself.
   */
  def transform(dsc: Discovery, shd: Sharder)(c: Context[PlatformEvent]): Context[Plan] =
    c match {
      case Context(d,NewTarget(target)) =>
        val work = handle.newTarget(target, shd)(d)
        Context(d, Distribute(work))

      case Context(d,NewFlask(f)) =>
        val (proposed, work) = handle.newFlask(f, shd)(d)
        Context(proposed, work)

      case Context(d,TerminatedTarget(uri)) =>
        Context(d, Ignore)

      case Context(d,TerminatedFlask(flask)) =>
        val tasks: Task[Seq[PlatformEvent]] =
          for {
            a <- dsc.listTargets
            b  = a.map(_._2).flatten
            c  = b.map(NewTarget(_))
          } yield c
        Context(d, Produce(tasks))

      case Context(d,NoOp) =>
        Context(d, Ignore)
    }

  /**
   * create the discovery stream by calling the discovery system and also
   * gathering a list of all the known work assigned to the flask as of
   * right now. difference the discovered work with the known work and then
   * produce `NewTarget` events for any remain, as they are not currently
   * being monitored.
   */
  def discovery(
    interval: Duration
  )(dsc: Discovery,
    gather: Distribution => Task[Distribution]
  ): Process[Task,Context[PlatformEvent]] =
    discover(dsc, interval).evalMap { case Context(a,b) =>
      for(dist <- gather(a)) yield {
        val current: Vector[Target] = dist.values.toVector.flatten
        val event: PlatformEvent =
          if(current.exists(_ == b)) NoOp
          else NewTarget(b)
        Context(dist, event)
      }
    }

  /********* edge of the world *********/

  /**
   * create a process that merges the discovery and lifecycle streams into a single
   * process, and then executes the mapping function to figure out what actions
   * should be executed (withou actually executing them).
   */
  def process(
    lifecycle: Flow[PlatformEvent],
    pollInterval: Duration
  )(dsc: Discovery,
    shd: Sharder,
    http: dispatch.Http
  ): Process[Task, Context[Plan]] =
    discovery(pollInterval)(dsc, collect(http)(_))
      .wye(lifecycle)(wye.merge)
      .map(transform(dsc,shd))

  // needs error handling
  def task(
    lifecycle: Flow[PlatformEvent],
    pollInterval: Duration
  )(dsc: Discovery,
    que: Queue[PlatformEvent],
    shd: Sharder,
    http: dispatch.Http,
    state: StateCache,
    effects: Sink[Task, Context[Plan]]
  ): Task[Unit] = {
    val ec: Sink[Task, Context[PlatformEvent]] =
      sinks.caching[PlatformEvent](state)

    val pc: Sink[Task, Context[Plan]] =
      sinks.caching[Plan](state)

    val lp: Flow[PlatformEvent] = que.dequeue.map(contextualise)
      .wye(lifecycle)(wye.merge)(Chemist.defaultExecutor)
      .observe(ec)
      // .observe(sink.lift(a => Task.delay(log.info(s"@@@@ $a"))))

    process(lp, pollInterval)(dsc,shd,http)
      .observe(pc)
      .to(effects).run
  }
}