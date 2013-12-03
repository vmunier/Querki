package models.system

import models._

import Thing._

import OIDs._

import SystemSpace._

import querki.values._

/**
 * The Ur-Thing, from which the entire world descends. Note that this is
 * its own parent!
 */
object UrThing extends ThingState(RootOID, systemOID, RootOID,
    toProps(
      setName("Thing"),
      // TODO: once we rework the UI some more, we probably can and should remove this Optional from here.
      // It is really only here to remind the Space author to think about whether something is a Model.
      (IsModelOID -> ExactlyOne(ElemValue(false, new DelegatingType(YesNoType))))
      )) 
{
  override def getProp(propId:OID)(implicit state:SpaceState):PropAndVal[_] = {
    // If we've gotten up to here and haven't found the property, use
    // the default:
    localOrDefault(propId)
  }
    
  override def getPropVal[VT, CT](prop:Property[VT, _])(implicit state:SpaceState):QValue = {
    localPropVal(prop).getOrElse(prop.default)
  }
  
  override def hasProp(propId:OID)(implicit state:SpaceState):Boolean = {
    props.contains(propId)
  }
    
  override def allProps(implicit state:SpaceState):Set[Property[_,_]] = localProps
  
  override def isAncestor(other:OID)(implicit state:SpaceState):Boolean = false
  
  override def hasModel = false
}
  

object Page extends ThingState(PageOID, systemOID, RootOID,
    toProps(
      setName("Simple-Page"),
      IsModelProp(true),
      DisplayTextProp(Optional.QNone)))

object SimpleThing extends ThingState(SimpleThingOID, systemOID, RootOID,
    toProps(
      setName("Simple-Thing"),
      IsModelProp(true)))

object PhotoBase extends ThingState(PhotoBaseOID, systemOID, RootOID,
    toProps(
      setName("Photograph-Base"),
      IsModelProp(true),
      DisplayTextProp("""
This is the Model for all uploaded photographs. You shouldn't try to base something on this directly --
just upload a photograph, and you'll get one of these.
""")),
    modules.time.TimeModule.epoch,
    Kind.Attachment
    )

object Bulleted extends ThingState(BulletedOID, systemOID, RootOID,
    toProps(
      setName("_bulleted"),
      ApplyMethod("\"\"* ____\"\""),
      DisplayTextProp("""    LIST -> _bulleted
          |This method takes a LIST, and render its elements as a bullet list, one per line. It is simply syntactic sugar for
          |    LIST -> \""* \____\"" """.stripMargin)))

object Commas extends ThingState(CommasMethodOID, systemOID, RootOID,
    toProps(
      setName("_commas"),
      ApplyMethod("""_join("", "")"""),
      DisplayTextProp("""    LIST -> _commas
          |This method takes a LIST, and render its elements comma-separated. It is simply syntactic sugar for
          |    LIST -> _join(\"", \"")""".stripMargin)))

object DisplayThingTree extends ThingState(DisplayThingTreeOID, systemOID, RootOID,
    toProps(
      setName("_displayThingTree"),
      ApplyMethod("""""[[_if(_isModel, ""{{_modelInTree:"")]]____[[_if(_isModel, "" [[_createInstanceLink -> _iconButton(""icon-plus-sign"", ""Create an Instance"")]]}}"")]]
{{indent:[[_children -> 
  _sort -> 
  _displayThingTree]]
}}
""""")))

object AllThings extends ThingState(AllThingsOID, systemOID, RootOID,
    toProps(
      setName("All Things"),
      DisplayTextProp("[[All Things]]"),
      ApplyMethod("""""{{_thingTree:
[[_currentSpace ->
  _externalRoots ->
  _sort ->
  _displayThingTree]]
}}""""")))

object AllProps extends ThingState(AllPropsThingOID, systemOID, RootOID,
    toProps(
      setName("All Properties"),
      DisplayTextProp("[[All Properties]]"),
      ApplyMethod("""""{{_thingTree:
[[_currentSpace ->
  _allProps ->
  _bulleted]]
}}
""""")))

