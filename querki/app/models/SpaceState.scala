package models

import system._

import OIDs._

import Thing._

/**
 * A Space is the Querki equivalent of a database -- a collection of related Things,
 * Properties and Types.
 * 
 * Note that, just like everything else, a Space is a special sort of Thing. It can
 * have Properties (including user-defined ones), and can potentially inherit from a
 * Model.
 * 
 * A SpaceState is a Space at a specific point in time. Operations are usually performed
 * on a SpaceState, to keep them consistent. Changes are sent to the Space, which generates
 * a new SpaceState from them.
 * 
 * TODO: implement Space inheritance -- that is, Apps.
 */
case class SpaceState(
    s:OID, 
    m:OID,
    pf:PropFetcher,
    owner:OID,
    name:String,
    // TODO: in principle, this is a List[SpaceState] -- there can be multiple ancestors:
    app:Option[SpaceState],
    types:Map[OID, PType[_]],
    spaceProps:Map[OID, Property[_,_]],
    things:Map[OID, ThingState],
    colls:Map[OID, Collection]) 
  extends Thing(s, s, m, Kind.Space, pf) 
{
  // Walks up the App tree, looking for the specified Thing of the implied type:
  def resolve[T <: Thing](tid:OID)(lookup: (SpaceState, OID) => Option[T]):T = {
    lookup(this, tid).getOrElse(
          app.map(_.resolve(tid)(lookup)).getOrElse(throw new Exception("Couldn't find " + tid)))
  }
  def typ(ptr:OID) = resolve(ptr) (_.types.get(_))
  def prop(ptr:OID) = resolve(ptr) (_.spaceProps.get(_))
  def thing(ptr:OID) = resolve(ptr) (_.things.get(_))
  def coll(ptr:OID) = resolve(ptr) (_.colls.get(_))
  
  def anything(oid:OID):Option[Thing] = {
    // TODO: this should do something more sensible if the OID isn't found at all:
    things.get(oid).orElse(
      spaceProps.get(oid).orElse(
        types.get(oid).orElse(
          colls.get(oid).orElse(
            selfByOID(oid).orElse(
              app.flatMap(_.anything(oid)))))))
  }
  
  def thingWithName(name:String, things:Map[OID, Thing]):Option[Thing] = {
    things.values.find { thing =>
      val thingNameOpt = thing.canonicalName
      thingNameOpt.isDefined && NameType.equalNames(thingNameOpt.get, name)
    }
  }
  
  def selfByOID(oid:OID):Option[Thing] = {
    if (oid == id) Some(this) else None
  }
  def spaceByName(tryName:String):Option[Thing] = {
    if (tryName == NameType.toInternal(name)) Some(this) else None
  }
  
  // TBD: should this try recognizing Display Names as well? I've confused myself that way once
  // or twice.
  // TBD: changed this to look up the app stack. That's clearly right sometimes, like in QL.
  // Is it always right?
  def anythingByName(rawName:String):Option[Thing] = {
    val name = NameType.toInternal(rawName)
    thingWithName(name, things).orElse(
      thingWithName(name, spaceProps).orElse(
        thingWithName(name, types).orElse(
          thingWithName(name, colls).orElse(
            spaceByName(name).orElse(
              app.flatMap(_.anythingByName(rawName)))))))
  }
  
  def anything(thingId:ThingId):Option[Thing] = {
    thingId match {
      case AsOID(oid) => anything(oid)
      case AsName(name) => anythingByName(name)
    }
  }
  
  def allProps:Map[OID, Property[_,_]] = if (app.isEmpty) spaceProps else spaceProps ++ app.get.allProps
  
  def allModels:Iterable[ThingState] = {
    implicit val s = this
    val myModels = things.values.filter(_.first(IsModelProp))
    if (app.isEmpty) {
      myModels
    } else {
      myModels ++ app.get.allModels
    }
  }
  
  /**
   * Given a Link Property, return all of the appropriate candidates for that property to point to.
   * 
   * The Property passed into here should usually be of LinkType -- while in theory that's not required,
   * it would be surprising for it to be used otherwise.
   */
  def linkCandidates(prop:Property[_,_]):Iterable[Thing] = {
    implicit val s = this
    
    val locals = linkCandidatesLocal(prop)
    if (app.isDefined && prop.hasProp(LinkAllowAppsProp) && prop.first(LinkAllowAppsProp))
      locals ++: app.get.linkCandidates(prop)
    else
      locals
  }

  /**
   * This enumerates all of the plausible candidates for the given property within this Space.
   */
  def linkCandidatesLocal(prop:Property[_,_]):Iterable[Thing] = {
    implicit val s = this
    
    // First, filter the candidates based on LinkKind:
    val allCandidates = if (prop.hasProp(LinkKindOID)) {
      val allowedKinds = prop.getPropVal(LinkKindProp).cv
      def fetchKind(wrappedVal:ElemValue):Iterable[Thing] = {
        val kind = LinkKindProp.pType.get(wrappedVal)
        kind match {
          case Kind.Thing => things.values
          case Kind.Property => spaceProps.values
          case Kind.Type => types.values
          case Kind.Collection => colls.values
          // TODO: distinguish Things and Attachments?
          case _ => Iterable.empty[Thing]
        }
      }
      (Iterable.empty[Thing] /: allowedKinds)((it, kind) => it ++: fetchKind(kind))
    } else {
      // No LinkKind specified, so figure that they only want Things:
      things.values
    }
    
    // Now, if they've specified a particular Model to be the limit of the candidate
    // tree -- essentially, they've specified what type you can link to -- filter for
    // that:
    if (prop.hasProp(LinkModelProp)) {
      val limit = prop.first(LinkModelProp)
      allCandidates filter (_.isAncestor(limit))
    } else {
      allCandidates
    }
  }
}
