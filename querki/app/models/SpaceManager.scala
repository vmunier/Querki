package models

import system._
import OIDs._

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import anorm._
import play.api._
import play.api.db._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.Configuration

class SpaceManager extends Actor {
  import models.system.SystemSpace
  import SystemSpace._
  import Space._
  
  // TODO: Add the local cache of Space States. This will require a bit of rethink about what
  // gets done in the Space Actor, and what belongs to the SpaceState itself, so that all
  // non-mutating operations can just got through the State.
  // TODO: this cache needs to age properly.
  // TODO: this needs a cap of how many states we will try to cache.
  
  // TEMP:
  val replyMsg = Play.configuration.getString("querki.test.replyMsg").get
  
  def getSpace(spaceId:OID):ActorRef = {
    val sid = Space.sid(spaceId)
 	// TODO: this *should* be using context.child(), but that doesn't exist in Akka
    // 2.0.2, so we have to wait until we have access to 2.1.0:
    //val childOpt = context.child(sid)
    val childOpt = context.children find (_.path.name == sid)
    childOpt match {
      case Some(child) => child
      case None => context.actorOf(Props[Space], sid)
    }
  }
  
  def receive = {
    case req:ListMySpaces => {
      //val results = spaceCache.values.filter(_.owner == req.owner).map(space => (space.id, space.name)).toSeq
      if (req.owner == SystemUserOID)
        sender ! MySpaces(Seq((AsName("System"), AsOID(systemOID), State.name)))
      else {
        // TODO: this involves DB access, so should be async using the Actor DSL
        val results = DB.withConnection { implicit conn =>
          val spaceStream = SQL("""
              select id, name, display from Spaces
              where owner = {owner}
              """).on("owner" -> req.owner.raw)()
          spaceStream.force.map { row =>
            val id = OID(row.get[Long]("id").get)
            val name = row.get[String]("name").get
            val display = row.get[String]("display").get
            (AsName(name), AsOID(id), display)
          }
        }
        sender ! MySpaces(results)
      }
    }

    case req:CreateSpace => {
      // TODO: technically, the legal name check should happen in the same transactions as
      // space creation, to avoid the just-barely-possible race condition of creating the
      // same name in two different sessions simultaneously. Extraordinarily unlikely, but
      // we should fix this.
      val errorMsg = legalSpaceName(req.owner, req.name)
      if (errorMsg.isEmpty) {
        // TODO: check that the owner hasn't run out of spaces he can create
        // TODO: this involves DB access, so should be async using the Actor DSL
        val (spaceId, spaceActor) = createSpace(req.owner, req.name)
        // Now, let the Space Actor finish the process once it is ready:
        spaceActor.forward(req)
      } else {
        sender ! ThingFailed(errorMsg.get)
      }
    }

    // TODO: CRITICAL: we need a pseudo-Space for System!
    // This clause is a pure forwarder for messages to a particular Space.
    // Is there a better way to do this?
    case req:SpaceMessage => {
      Logger.info("SpaceMgr got " + req)
      Logger.info("Config message is " + replyMsg)
      // TODO: cope with messages in name style instead
      req match {
        case SpaceMessage(_, _, AsOID(spaceId)) => getSpace(spaceId).forward(req)
        case SpaceMessage(_, ownerId, AsName(spaceName)) => {
          val spaceOpt = getSpaceByName(ownerId, spaceName)
          // TODO: the error clause below potentially leaks information about whether a
          // give space exists for an owner. Examine the various security paths holistically.
          spaceOpt map getSpace map { _.forward(req) } getOrElse { sender ! ThingFailed("Not a legal path") }
        }
      }
    }
  }
  
  private def getSpaceByName(ownerId:OID, name:String):Option[OID] = {
    DB.withTransaction { implicit conn =>
      val rowOption = SQL("""
          SELECT id from Spaces WHERE owner = {ownerId} AND name = {name}
          """).on("ownerId" -> ownerId.raw, "name" -> NameType.canonicalize(name))().headOption
      rowOption.map(row => OID(row.get[Long]("id").get))
    }
  }
  
  private def legalSpaceName(ownerId:OID, name:String):Option[String] = {
    def numWithName = DB.withTransaction { implicit conn =>
      SQL("""
          SELECT COUNT(*) AS c from Spaces WHERE owner = {ownerId} AND name = {name}
          """).on("ownerId" -> ownerId.raw, "name" -> NameType.canonicalize(name)).apply().headOption.get.get[Long]("c").get
    }
    if (!NameProp.validate(name))
      Some("That's not a legal name for a Space")
    else if (numWithName > 0) {
      Logger.info("numWithName = " + numWithName)
      Some("You already have a Space with that name")
    } else
      None
  }
  
  private def createSpace(owner:OID, display:String) = {
    val name = NameType.canonicalize(display)
    val spaceId = OID.next
    Logger.info("Creating new Space with OID " + Space.thingTable(spaceId))
    // Why the replace() here? It looks to me like the .on() function always
    // surrounds its parameter with quotes, and I don't think that works in
    // the table name. Sad.
    DB.withTransaction { implicit conn =>
      SpaceSQL(spaceId, """
          CREATE TABLE {tname} (
            id bigint NOT NULL,
            model bigint NOT NULL,
            kind int NOT NULL,
            props MEDIUMTEXT NOT NULL,
            PRIMARY KEY (id))
          """).executeUpdate()
      AttachSQL(spaceId, """
          CREATE TABLE {tname} (
            id bigint NOT NULL,
            mime varchar(127) NOT NULL,
            size int NOT NULL,
            content mediumblob NOT NULL,
            PRIMARY KEY (id))
          """).executeUpdate()
      SQL("""
          INSERT INTO Spaces
          (id, shard, name, display, owner, size) VALUES
          ({sid}, {shard}, {name}, {display}, {ownerId}, 0)
          """).on("sid" -> spaceId.raw, "shard" -> 1.toString, "name" -> name,
                  "display" -> display, "ownerId" -> owner.raw).executeUpdate()
      val initProps = Thing.toProps(Thing.setName(name), DisplayNameProp(display))()
      Space.createThingInSql(spaceId, spaceId, RootOID, Kind.Space, initProps, State)
    }
    val spaceActor = context.actorOf(Props[Space], name = Space.sid(spaceId))
    (spaceId, spaceActor)
  }
}

object SpaceManager {
  // I don't love having to hold a static reference like this, but Play's statelessness
  // probably requires that. Should we instead be holding a Path, and looking it up
  // each time?
  lazy val ref = Akka.system.actorOf(Props[SpaceManager], name="SpaceManager")
  
  // This is probably over-broad -- we're going to need the timeout to push through to
  // the ultimate callers.
  implicit val timeout = Timeout(5 seconds)
  
  // Send a message to the SpaceManager, expecting a return of type A to be
  // passed into the callback. This wraps up the messy logic to go from a
  // non-actor-based Play environment to the SpaceManager. We'll likely
  // generalize it further eventually.
  //
  // Type A is the response we expect to get back from the message, which will
  // be sent to the given callback.
  //
  // Type B is the type of the callback. I'm a little surprised that this isn't
  // inferred -- I suspect I'm doing something wrong syntactically.
  def ask[A,B](msg:SpaceMgrMsg)(cb: A => B)(implicit m:Manifest[A]):Future[B] = {
    // Why isn't this compiling properly? We should be getting an implicit import of ?
//    (ref ? msg).mapTo[A].map(cb)
    akka.pattern.ask(ref, msg).mapTo[A].map(cb)
  }
}
