/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.efdi.server.pubsub

import akka.actor._
import akka.pattern.ask
import com.debiki.core.Prelude._
import com.debiki.core._
import debiki.{ReactJson, Globals}
import play.api.libs.json.{JsNull, JsValue}
import play.{api => p}
import play.api.libs.json.Json
import play.api.libs.ws.{WSResponse, WS}
import play.api.Play.current
import scala.collection.{mutable, immutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import ReactJson.JsUser


sealed trait Message {
  def siteId: SiteId
  def toJson: JsValue
  def notifications: Notifications
}


// Remove? Use StorePatchMessage instead?
case class NewPageMessage(
  siteId: SiteId,
  pageId: PageId,
  pageRole: PageRole,
  notifications: Notifications) extends Message {

  def toJson = JsNull
}


case class StorePatchMessage(
  siteId: SiteId,
  toUsersViewingPage: PageId,
  json: JsValue,
  notifications: Notifications) extends Message {

  def toJson = JsNull
}


object PubSub {

  // Not thread safe; only needed in integration tests.
  var testInstanceCounter = 1


  /** Starts a PubSub actor (only one is needed in the whole app).
    */
  def startNewActor(actorSystem: ActorSystem): (PubSubApi, StrangerCounterApi) = {
    val actorRef = actorSystem.actorOf(Props(
      new PubSubActor()), name = s"PubSub-$testInstanceCounter")
    actorSystem.scheduler.schedule(60 seconds, 10 seconds, actorRef, DeleteInactiveSubscriptions)
    testInstanceCounter += 1
    (new PubSubApi(actorRef), new StrangerCounterApi(actorRef))
  }

}



class PubSubApi(private val actorRef: ActorRef) {

  private val timeout = 10 seconds

  def onUserSubscribed(siteId: SiteId, user: User, browserIdData: BrowserIdData) {
    actorRef ! UserSubscribed(siteId, user, browserIdData)
  }

  def unsubscribeUser(siteId: SiteId, user: User, browserIdData: BrowserIdData) {
    actorRef ! UnsubscribeUser(siteId, user, browserIdData)
  }

  def publish(message: Message) {
    actorRef ! PublishMessage(message)
  }

  def listOnlineUsers(siteId: SiteId): Future[(immutable.Seq[User], Int)] = {
    val response: Future[Any] = (actorRef ? ListOnlineUsers(siteId))(timeout)
    response.map(_.asInstanceOf[(immutable.Seq[User], Int)])
  }
}


class StrangerCounterApi(private val actorRef: ActorRef) {

  private val timeout = 10 seconds

  def tellStrangerSeen(siteId: SiteId, browserIdData: BrowserIdData) {
    actorRef ! StrangerSeen(siteId, browserIdData)
  }
}


private case class PublishMessage(message: Message)
private case class UserSubscribed(siteId: SiteId, user: User, browserIdData: BrowserIdData)
private case class UnsubscribeUser(siteId: SiteId, user: User, browserIdData: BrowserIdData)
private case class ListOnlineUsers(siteId: SiteId)
private case object DeleteInactiveSubscriptions

private case class StrangerSeen(siteId: SiteId, browserIdData: BrowserIdData)



/** Publishes events to browsers via e.g. long polling or WebSocket. Reqiures nginx and nchan.
  * Assumes an nginx-nchan publish endpoint is available at: 127.0.0.1:80/-/pubsub/publish/
  * (and nginx should have been configured to allow access from localhost only).
  *
  * Later:? Poll nchan each minute? to find out which users have disconnected?
  * ((Could add an nchan feature that tells the appserver about this, push not poll?))
  */
class PubSubActor extends Actor {

  /** Tells when subscriber subscribed. Subscribers are sorted by perhaps-inactive first.
    * We'll push messages only to users who have subscribed (i.e. are online and have
    * connected to the server via e.g. WebSocket).
    */
  private val subscribersBySite =
    mutable.HashMap[SiteId, mutable.LinkedHashMap[UserId, UserAndWhen]]()

  private class UserAndWhen(val user: User, val when: When)

  private def perSiteSubscribers(siteId: SiteId) =
    // Use a LinkedHashMap because sort order = insertion order.
    subscribersBySite.getOrElseUpdate(siteId, mutable.LinkedHashMap[UserId, UserAndWhen]())

  // Could check what is Nchan's long-polling inactive timeout, if any?
  private val DeleteAfterInactiveMillis = 10 * OneMinuteInMillis

  private val strangerCounter = new io.efdi.server.stranger.StrangerCounter()


  def receive = {
    case UserSubscribed(siteId, user, browserIdData) =>
      strangerCounter.removeStranger(siteId, browserIdData)
      publishUserPresence(siteId, user, Presence.Active)
      subscribeUser(siteId, user)
    case UnsubscribeUser(siteId, user, browserIdData) =>
      // Don't bump the stranger counter, it's fairly likely that the user left for real?
      // Also, increasing it gives everyone the impression that the server knows the user
      // didn't really leave, but rather says, reading, but logged out.
      unsubscribeUser(siteId, user)
      publishUserPresence(siteId, user, Presence.Away)
    case PublishMessage(message: Message) =>
      publishStuffAndNotfs(message)
    case ListOnlineUsers(siteId) =>
      sender ! listUsersOnline(siteId)
    case DeleteInactiveSubscriptions =>
      deleteInactiveSubscriptions()
      strangerCounter.deleteOldStrangers()
    case StrangerSeen(siteId, browserIdData) =>
      strangerCounter.addStranger(siteId, browserIdData)
  }


  private def subscribeUser(siteId: SiteId, user: User) {
    val userAndWhenMap = perSiteSubscribers(siteId)
    // Remove and reinsert, so inactive users will be the first ones found when iterating.
    userAndWhenMap.remove(user.id)
    userAndWhenMap.put(user.id, new UserAndWhen(user, When.now()))
  }


  private def unsubscribeUser(siteId: SiteId, user: User) {
    // COULD tell Nchan about this too
    perSiteSubscribers(siteId).remove(user.id)
  }


  private def publishUserPresence(siteId: SiteId, user: User, presence: Presence) {
    // dupl code [7UKY74]
    val siteDao = Globals.siteDao(siteId)
    val site = siteDao.loadSite()
    val canonicalHost = site.canonicalHost.getOrDie(
      "EsE2WUV43", s"Site lacks canonical host: $site")

    val userAndWhenById = perSiteSubscribers(siteId)
    if (userAndWhenById.contains(user.id))
      return

    val userIds = userAndWhenById.values.map(_.user.id).toSet
    sendPublishRequest(canonicalHost.hostname, userIds, "presence", Json.obj(
      "user" -> JsUser(user, Some(presence)),
      "numOnlineStrangers" -> strangerCounter.countStrangers(siteId)))
  }


  private def publishStuffAndNotfs(message: Message) {
    // dupl code [7UKY74]
    val siteDao = Globals.siteDao(message.siteId)
    val site = siteDao.loadSite()
    val canonicalHost = site.canonicalHost.getOrDie(
      "EsE7UKFW2", s"Site lacks canonical host: $site")

    SHOULD // only publish notfs to connected users
    message.notifications.toCreate foreach { notf =>
      COULD_OPTIMIZE // later: do only 1 call to siteDao, for all notfs.
      val notfsJson = siteDao.readOnlyTransaction { transaction =>
        ReactJson.notificationsToJson(Seq(notf), transaction).notfsJson
      }
      sendPublishRequest(canonicalHost.hostname, Set(notf.toUserId), "notifications", notfsJson)
    }

    message match {
      case patchMessage: StorePatchMessage =>
        val userIds = userViewingPage(patchMessage.siteId, pageId = patchMessage.toUsersViewingPage)
        sendPublishRequest(canonicalHost.hostname, userIds, "storePatch", patchMessage.json)
      case x =>
        unimplemented(s"Publishing ${classNameOf(x)} [EsE4GPYU2]")
    }
  }


  private def userViewingPage(siteId: SiteId, pageId: PageId): Iterable[UserId] = {
    // For now, publ to ... everyone. Crazy. Just testing.
    SHOULD // only publish to connected users who views / recently-viewed-&-listens-to pageId.
    perSiteSubscribers(siteId).keySet
  }


  private def sendPublishRequest(hostname: String, toUserIds: Iterable[UserId], tyype: String,
        json: JsValue) {
    // Currently nchan doesn't support publishing to many channels with one single request.
    // (See the Channel Multiplexing section here: https://nchan.slact.net/
    // it says: "Publishing to multiple channels from one location is not supported")
    COULD // create an issue about supporting that? What about each post data text line = a channel,
    // and a blank line separates channels from the message that will be sent to all these channels?
    toUserIds foreach { userId =>
      WS.url(s"http://localhost/-/pubsub/publish/$userId")
        .withVirtualHost(hostname)
        .post(Json.obj("type" -> tyype, "data" -> json).toString)
        .map(handlePublishResponse)
        .recover({
          case ex: Exception =>
            p.Logger.warn(s"Error publishing to browsers [EsE0KPU31]", ex)
        })
    }
  }


  private def handlePublishResponse(response: WSResponse) {
    if (response.status < 200 || 299 < response.status) {
      p.Logger.warn(o"""Bad nchan status code after sending publish request [EsE9UKJ2]:
        ${response.status} ${response.statusText} — see the nginx error log for details?
        Response body: '${response.body}""")
    }
  }


  private def listUsersOnline(siteId: SiteId): (immutable.Seq[User], Int) = {
    val onlineUsers = perSiteSubscribers(siteId).values.map(_.user).to[immutable.Seq]
    val numStrangers = strangerCounter.countStrangers(siteId)
    (onlineUsers, numStrangers)
  }


  private def deleteInactiveSubscriptions() {
    val now = When.now()
    for ((siteId, userAndWhenMap) <- subscribersBySite) {
      // LinkedHashMap sort order = perhaps-inactive first.
      // COULD implement `removeWhile` [removewhile]
      while (true) {
        val ((userId, userAndWhen)) = userAndWhenMap.headOption getOrElse {
          return
        }
        if (now.millisSince(userAndWhen.when) < DeleteAfterInactiveMillis)
          return

        userAndWhenMap.remove(userId)
      }
    }
  }

}
