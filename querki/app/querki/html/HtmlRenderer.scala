package querki.html

import scala.xml._

import play.api.Logger
import play.api.data.Form
import play.api.templates.Html

import models._
import models.system._

import querki.ecology._

import querki.values._

import RenderSpecialization._

object MOIDs extends EcotIds(26)

/**
 * This is the top level object that knows about HTML. All rendering of Things into HTML,
 * and interpretation of HTML forms, should pass through here.
 * 
 * TODO: we are a *long* ways from having this working right -- there's a ton of
 * refactoring to do. But it's a start.
 * 
 * TODO: there should be a trait called something like InputRenderer, which this derives from,
 * generalizing the concept of rendering and response.
 */
class HtmlRendererEcot(e:Ecology) extends QuerkiEcot(e) with HtmlRenderer {
  
  lazy val Links = interface[querki.links.Links]
  lazy val Tags = interface[querki.tags.Tags]
  
  lazy val NewTagSetType = Tags.NewTagSetType
  
  /*********************************
   * PUBLIC API
   *********************************/
  
  def renderPropertyInput(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal, specialization:Set[RenderSpecialization] = Set(Unspecialized)):Html = {
    val cType = prop.cType
    val pType = prop.pType
    val rendered = renderSpecialized(cType, pType, state, prop, currentValue, specialization).getOrElse(cType.renderInput(prop, state, currentValue, pType))
    val xml3 = addEditorAttributes(rendered, currentValue.inputControlId, prop.id, currentValue.inputControlId, currentValue.on.map(_.id.toThingId))
    // TODO: this is *very* suspicious, but we need to find a solution. RenderTagSet is trying to pass JSON structures in the
    // value field, but for that to be JSON-legal, the attributes need to be single-quoted, and the strings in them double-quoted.
    // That isn't the way things come out here, so we're kludging, but I worry about potential security holes...
    val xmlFixedQuotes = xml3.toString.replace("\'", "&#39;").replace("\"", "\'").replace("&quot;", "\"")
    Html(xmlFixedQuotes)
  }
  
  def propValFromUser(prop:Property[_,_], str:String):QValue = {
    handleSpecialized(prop, str).getOrElse(prop.cType.fromUser(str, prop, prop.pType))
  }
  
  // TODO: refactor this with Collection.fromUser():
  def propValFromUser(prop:Property[_,_], on:Option[Thing], form:Form[_], context:QLContext):FormFieldInfo = {
    if (prop.cType == QSet && (prop.pType.isInstanceOf[NameType] || prop.pType == LinkType || prop.pType == NewTagSetType)) {
      handleTagSet(prop, on, form, context)
    } else {
      val fieldIds = FieldIds(on, prop)
      val spec = for (
        formV <- form(fieldIds.inputControlId).value;
        specialized <- handleSpecializedForm(prop, formV)
          )
        yield specialized
      spec.getOrElse(prop.cType.fromUser(on, form, prop, prop.pType, context.state))
    }
  }
  
  /*********************************
   * INTERNALS
   *********************************/
  
  def addClasses(elem:Elem, addedClasses:String):Elem = {
    val classAttr = elem.attribute("class").map(_ :+ Text(" " + addedClasses)).getOrElse(Text(addedClasses))
    elem % Attribute("class", classAttr, Null)
  }
  
  def addEditorAttributes(elem:Elem, name:String, prop:OID, id:String, thingOpt:Option[String]):Elem = {
    // If there is already a name specified, leave it in place. This is occasionally *very* important, as
    // in renderOptionalYesNo -- that needs the usual name to be on the *buttons*, not on the wrapper:
    val newName = elem.attribute("name").map(_.head.text).getOrElse(name)
    val xml2 = addClasses(elem, "propEditor") %
    	Attribute("name", Text(newName),
    	Attribute("data-prop", Text(prop.toThingId),
    	Attribute("data-propId", Text(prop.toString),
    	Attribute("id", Text(id), Null))))
    val xml3 = thingOpt match {
      case Some(thing) => xml2 % Attribute("data-thing", Text(thing), Null)
      case None => xml2
    }
    xml3
  }
  
  def renderSpecialized(cType:Collection, pType:PType[_], state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal, specialization:Set[RenderSpecialization]):Option[Elem] = {
    // TODO: make this more data-driven. There should be a table of these.
    if (cType == Optional && pType == YesNoType)
      Some(renderOptYesNo(state, prop, currentValue))
    else if (cType == Optional && pType == LinkType)
      Some(renderOptLink(state, prop, currentValue))
    else if (cType == QSet && (pType.isInstanceOf[NameType] || pType == LinkType || pType == NewTagSetType)) {
      if (specialization.contains(PickList))
        Some(renderPickList(state, prop, currentValue, specialization))
      else
        Some(renderTagSet(state, prop, currentValue))
    } else
      None
  }
  
  def renderOptYesNo(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal):Elem = {
    val pType = YesNoType
    val pair = currentValue.effectiveV.map(propVal => if (propVal.cv.isEmpty) (false, pType.default) else (true, propVal.first)).getOrElse((false, pType.default))
    val isSet = pair._1
    val v = pType.get(pair._2)
    
    def oneButton(label:String, value:String, checked:Boolean):NodeSeq = {
      def inputElem = {
        if (checked)
          <button type="button" class="radioBtn btn btn-primary active" value={value}>{label}</button>
        else
          <button type="button" class="radioBtn btn btn-primary" value={value}>{label}</button>
      }
      val id = currentValue.inputControlId + "-" + label
      val name = currentValue.inputControlId
      // Note that, to work for interactive controls, the special AJAX properties must be on the individual buttons!
      addEditorAttributes(inputElem, name, prop.id, id, currentValue.on.map(_.id.toThingId)) // ++ <label for={id}>{label}</label>
    }
    
      <span class="btn-group" data-toggle="buttons-radio" name={currentValue.inputControlId + "-wrapper"}>{oneButton("Yes", "true", (isSet && v))}{oneButton("Maybe", "maybe", (!isSet))}{oneButton("No", "false", (isSet && !v))}</span>
  }
  
  def renderOptLink(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal):Elem = {
    val pType = LinkType
    val pair = currentValue.effectiveV.map(propVal => if (propVal.cv.isEmpty) (false, pType.default) else (true, propVal.first)).getOrElse((false, pType.default))
    val isSet = pair._1
    val v = pType.get(pair._2)
    
    val results = <select class="_linkSelect"> 
      <option value={UnknownOID.id.toString}>Nothing selected</option>
      {
      LinkType.renderInputXmlGuts(prop, state, currentValue, ElemValue(v, LinkType))
    } </select>
    results
  }
  
  def getTagSetNames(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal):Option[Iterable[(String,String)]] = {
    val currentV = currentValue.effectiveV
    val pt = prop.pType
    
    def getKeyAndVal(elem:ElemValue):(String, String) = {
      pt match {
        case linkType:LinkType => {
          val oid = linkType.get(elem)
          // TODO: cheating! This should go through LinkType.follow, but we don't have a Context yet:
          val tOpt = state.anything(oid)
          val name = tOpt.map(_.displayName).getOrElse(oid.toThingId.toString)
          (oid.toString, name)
        }
        case nameType:NameType => {
          val name = nameType.get(elem)
          (name, name)          
        }
        case NewTagSetType => {
          val name = NewTagSetType.get(elem).text
          (name, name)                    
        }
        case _ => throw new Exception("renderTagSet got unexpected type " + pt)
      }
    }
    currentV.map(_.cv.map(getKeyAndVal(_)))    
  }
  
  def JSONescape(str:String):String = {
    str.replace("\\", "\\\\").replace("\"", "\\\"")
  }
  
  def renderTagSet(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal):Elem = {
    val rawList = getTagSetNames(state, prop, currentValue)
    
    // We treat names/tags and links a bit differently, although they look similar on the surface:
    val isNameType = prop.pType.isInstanceOf[NameType] || prop.pType == NewTagSetType
    val current = "[" + rawList.map(_.map(keyVal => "{\"display\":\"" + JSONescape(keyVal._2) + "\", \"id\":\"" + JSONescape(keyVal._1) + "\"}").mkString(", ")).getOrElse("") + "]"
    <input class="_tagSetInput" data-isNames={isNameType.toString} type="text" data-current={current}></input>
  }
  
  /**
   * This is an alternate renderer for Tag/List Sets. It displays a list of *all* of the candidate Things
   * (defined by the Link Model), and lets you choose them by checkboxes. A primitive first cut, but useful.
   */
  def renderPickList(state:SpaceState, prop:Property[_,_], currentValue:DisplayPropVal, specialization:Set[RenderSpecialization]):Elem = {
    implicit val s = state
    val instancesOpt = for (
        propAndVal <- prop.getPropOpt(Links.LinkModelProp);
        modelOID <- propAndVal.firstOpt
        )
      yield (modelOID, state.descendants(modelOID, false, true))
      
    instancesOpt match {
      case Some((modelOID, allInstances)) => {
        val sortedInstances = allInstances.toSeq.sortBy(_.displayName).zipWithIndex
        val rawList = getTagSetNames(state, prop, currentValue)
        val currentMap = rawList.map(_.toMap)
        val isNameType = prop.pType.isInstanceOf[NameType]
        
        val listName = currentValue.inputControlId + "_values"
        
        def isListed(item:Thing):Boolean = {
          currentMap match {
            case Some(map) => {
              if (isNameType) {
                val name = for (
                    propAndVal <- item.getPropOpt(Core.NameProp);
                    name <- propAndVal.firstOpt
                      )
                  yield name
                name.map(map.contains(_)).getOrElse(false)
              } else {
                map.contains(item.id.toString)
              }
            }
            case None => false
          }
        }
    
        <form><ul class="_listContent"> {
          sortedInstances.map { pair =>
            val (instance, index) = pair
            <li>{
            if (isListed(instance))
              Seq(<input name={s"$listName[$index]"} value={instance.id.toString} type="checkbox" checked="checked"></input>, Text(" " + instance.displayName))
            else
              Seq(<input name={s"$listName[$index]"} value={instance.id.toString} type="checkbox"></input>, Text(" " + instance.displayName))
            }</li>
          }
        } </ul> {
          if (specialization.contains(WithAdd)) {
            <div class="input-append">
              <input type="text" class="_quickCreateProp" placeholder="New Item Name" data-model={modelOID.toString} data-propid={models.system.OIDs.DisplayNameOID.toString} data-addtolist="true"></input>
              <button type="button" class="btn _quickCreate">Add</button>
            </div>
          }
        } </form>
      }
      // TODO: we need a better way to specify this warning
      case None => <p class="warning">Can't display a Pick List for a Set that doesn't have a Link Model!</p>
    }
  }
  
  def handleSpecialized(prop:Property[_,_], newVal:String):Option[QValue] = {
    if (prop.cType == Optional && prop.pType == YesNoType)
      Some(handleOptional(prop, newVal, YesNoType, (_ == "maybe")))
    else if (prop.cType == Optional && prop.pType == LinkType)
      Some(handleOptional(prop, newVal, LinkType, (OID(_) == UnknownOID)))
    else
      None
  }
  
  def handleOptional(prop:Property[_,_], newVal:String, pType:PType[_], isEmpty:String => Boolean):QValue = {
    if (isEmpty(newVal))
      Optional.default(pType)
    else
      Optional(pType.fromUser(newVal))
  }
  
  // TODO: refactor this together with the above. It's going to require some fancy type math, though:
  def handleSpecializedForm(prop:Property[_,_], newVal:String):Option[FormFieldInfo] = {
    if (prop.cType == Optional && prop.pType == YesNoType)
      Some(handleOptionalForm(prop, newVal, YesNoType, (_ == "maybe")))
    else if (prop.cType == Optional && prop.pType == LinkType)
      Some(handleOptionalForm(prop, newVal, LinkType, (OID(_) == UnknownOID)))
    else
      None
  }
  
  def handleOptionalForm(prop:Property[_,_], newVal:String, pType:PType[_], isNone:String => Boolean):FormFieldInfo = {
    if (isNone(newVal))
      // This is a bit subtle: there *is* a value, which is "None"
      FormFieldInfo(prop, Some(Core.QNone), false, true)
    else
      FormFieldInfo(prop, Some(Optional(pType.fromUser(newVal))), false, true)
  }
  
  // TODO: TagSets come from Manifest, and the end result is similar to QList.fromUser(). Figure out
  // how to refactor these together, if possible.
  def handleTagSet(prop:Property[_,_], on:Option[Thing], form:Form[_], context:QLContext):FormFieldInfo = {
    val fieldIds = FieldIds(on, prop)
    // TODO: this stuff testing for empty isn't really type-specific -- indeed, it is handling the button that is
    // rendered in editThing.html. So it probably belongs at a higher level?
    val empty = form(fieldIds.emptyControlId).value map (_.toBoolean) getOrElse false
    if (empty) {
      FormFieldInfo(prop, None, true, true)
    } else {
      val pt = prop.pType
      val oldListName = fieldIds.inputControlId + "_values"
      val oldList = form(oldListName)
      val oldIndexes = oldList.indexes
      val oldRaw =
        for (i <- oldIndexes;
             v <- oldList("[" + i + "]").value)
          yield v
      // TODO: some nasty abstraction breakage here. We shouldn't know that the internal is List:
      val oldVals = oldRaw.map(pt.fromUser(_)).toList
      FormFieldInfo(prop, Some(Core.makeSetValue(oldVals, pt, context)), false, true)
    }
  }
}