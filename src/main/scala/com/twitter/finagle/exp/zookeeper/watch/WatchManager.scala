package com.twitter.finagle.exp.zookeeper.watch

import com.twitter.finagle.exp.zookeeper.WatchEvent
import com.twitter.util.Promise
import scala.collection.mutable

/**
 * WatchManager is used to manage watches by keeping in memory the map
 * of active watches.
 * @param chroot default user chroot
 */
private[finagle] class WatchManager(chroot: String, autoWatchReset: Boolean) {
  val dataWatches: mutable.HashMap[String, Promise[WatchEvent]] =
    mutable.HashMap()
  val existsWatches: mutable.HashMap[String, Promise[WatchEvent]] =
    mutable.HashMap()
  val childWatches: mutable.HashMap[String, Promise[WatchEvent]] =
    mutable.HashMap()

  def getDataWatches = this.synchronized(dataWatches)
  def getExistsWatches = this.synchronized(existsWatches)
  def getChildWatches = this.synchronized(childWatches)

  private[this] def addWatch(
    map: mutable.HashMap[String, Promise[WatchEvent]],
    path: String): Promise[WatchEvent] = {

    map synchronized {
      map.getOrElse(path, {
        val p = Promise[WatchEvent]()
        map += path -> p
        p
      })
    }
  }

  def clearWatches() {
    this.synchronized {
      dataWatches.clear()
      existsWatches.clear()
      childWatches.clear()
    }
  }

  private[this] def findWatch(
    map: mutable.HashMap[String, Promise[WatchEvent]],
    event: WatchEvent) {

    map synchronized {
      map.get(event.path) match {
        case Some(promise) =>
          promise.setValue(event)
          map -= event.path
        case None =>
      }
    }
  }

  def register(path: String, watchType: Int): Promise[WatchEvent] = {
    watchType match {
      case Watch.Type.data => addWatch(dataWatches, path)
      case Watch.Type.exists => addWatch(existsWatches, path)
      case Watch.Type.child => addWatch(childWatches, path)
    }
  }

  /**
   * We use this to process every watches events that comes in
   * @param watchEvent the watch event that was received
   * @return
   */
  def process(watchEvent: WatchEvent) {
    val event = WatchEvent(
      watchEvent.typ,
      watchEvent.state,
      watchEvent.path.substring(chroot.length))

    event.typ match {
      case Watch.EventType.NONE =>
        if (event.state != Watch.State.SYNC_CONNECTED)
          if (!autoWatchReset) clearWatches()

      case Watch.EventType.NODE_CREATED | Watch.EventType.NODE_DATA_CHANGED =>
        findWatch(dataWatches, event)
        findWatch(existsWatches, event)

      case Watch.EventType.NODE_DELETED =>
        findWatch(dataWatches, event)
        findWatch(existsWatches, event)
        findWatch(childWatches, event)

      case Watch.EventType.NODE_CHILDREN_CHANGED =>
        findWatch(childWatches, event)

      case _ => throw new RuntimeException(
        "Unsupported Watch.EventType came during WatchedEvent processing")
    }
  }
}