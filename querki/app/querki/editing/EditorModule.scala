package querki.editing

import models.{Kind, OID, Property, Thing, ThingState, Wikitext}
import models.Thing.{PropFetcher, setName, toProps}

import models.system.{SingleContextMethod, SystemProperty}
import models.system.{ExactlyOne, QList}
import models.system.{LargeTextType, LinkType, QLText}
import models.system.{AppliesToKindProp, InstanceEditPropsProp, IsModelProp, PropDetails, PropSummary}
import models.system.OIDs.sysId

import ql.{QLCall, QLParser, QLPhrase}

import querki.html.RenderSpecialization._

import querki.util._
import querki.values._

import modules.Module

class EditorModule(val moduleId:Short) extends Module {
  object MOIDs {
    // Previously in System
    val EditMethodOID = sysId(42)
    val EditOrElseMethodOID = sysId(82)
    
    val EditAsPickListOID = moid(1)
    val InstanceEditViewOID = moid(2)
  }
  import MOIDs._
  
  /***********************************************
   * PROPERTIES
   ***********************************************/
  
  lazy val instanceEditViewProp = new SystemProperty(InstanceEditViewOID, LargeTextType, ExactlyOne,
      toProps(
        setName("Instance Edit View"),
        PropSummary("Defines the Edit View for Instances of this Model"),
        PropDetails("""Sometimes, you want to customize your editing experience -- to make things easier or more
            |efficient, or prettier for your users. Regardless of the reason, this Property gives you complete
            |control.
            |
            |This is an arbitrary Large Text, which is shown whenever you say
            |[[_code(""[[My Instance._edit]]"")]]
            |The contents are up to you, but it should usually contain _edit functions for each Property you
            |want to be editable.""".stripMargin)))

  abstract class EditMethodBase(id:OID, pf:PropFetcher) extends SingleContextMethod(id, pf)
  {
    def specialization(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      params:Option[Seq[QLPhrase]]):Set[RenderSpecialization] = Set(Unspecialized)
  
    def cantEditFallback(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      params:Option[Seq[QLPhrase]]):QValue
  
    def applyToPropAndThing(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      params:Option[Seq[QLPhrase]]):QValue =
    {
      mainContext.request.requester match {
        case Some(requester) if (mainContext.state.canEdit(requester, mainThing.id)) => {
          val currentValue = mainThing.getDisplayPropVal(prop)(mainContext.state)
	      // TODO: conceptually, this is a bit off -- the rendering style shouldn't be hard-coded here. We
  	      // probably need to have the Context contain the desire to render in HTML, and delegate to the
	      // HTML renderer indirectly. In other words, the Context should know the renderer to use, and pass
	      // that into here:
	      val inputControl = querki.html.HtmlRenderer.renderPropertyInput(mainContext.state, prop, currentValue, 
	          specialization(mainContext, mainThing, partialContext, prop, params))
	      HtmlValue(inputControl)    
        }
        case _ => cantEditFallback(mainContext, mainThing, partialContext, prop, params)
      }
    }
    
    /**
     * This wrapper creates the actual layout bits for the default Instance Editor. Note that it is *highly*
     * dependent on the styles defined in main.css!
     */
    case class EditorPropLayout(prop:Property[_,_]) {
      def span = prop.editorSpan
      def layout = s"""{{span$span:
      |{{_propTitle: ${prop.displayName}:}}
      |
      |[[${prop.toThingId}._edit]]
      |}}
      |""".stripMargin
    }
    
    case class EditorRowLayout(props:Seq[EditorPropLayout]) {
      def span = (0 /: props) { (sum, propLayout) => sum + propLayout.span }
      def layout = s"""{{row-fluid:
    		  |${props.map(_.layout).mkString}
              |}}
    		  |""".stripMargin
    }
    
    // This hard-coded number comes from Bootstrap, and is pretty integral to it:
    val maxSpanPerRow = 12
    
    /**
     * This takes the raw list of property layout objects, and breaks it into rows of no more
     * than 12 spans each.
     */
    def splitRows(propLayouts:Seq[EditorPropLayout]):Seq[EditorRowLayout] = {
      (Seq(EditorRowLayout(Seq.empty)) /: propLayouts) { (rows, nextProp) =>
        val currentRow = rows.last
        if ((currentRow.span + nextProp.span) > maxSpanPerRow)
          // Need a new row
          rows :+ EditorRowLayout(Seq(nextProp))
        else
          // There is room to fit it into the current row
          rows.take(rows.length - 1) :+ currentRow.copy(currentRow.props :+ nextProp)
      }
    }
    
    def propsToEditForThing(thing:Thing, state:SpaceState):Seq[Property[_,_]] = {
      implicit val s = state
      val result = for (
        propsToEdit <- thing.getPropOpt(InstanceEditPropsProp);
        propIds = propsToEdit.v.rawList(LinkType);
        props = propIds.map(state.prop(_)).flatten    
          )
        yield props

      // TODO: if there is no InstanceEditPropsProp, build it from the Thing and Model:
      result.getOrElse(???)
    }
    
    def editorLayoutForThing(thing:Thing, state:SpaceState):QLText = {
      implicit val s = state
      thing.getPropOpt(instanceEditViewProp).flatMap(_.v.firstTyped(LargeTextType)) match {
        // There's a predefined Instance Edit View, so use that:
        case Some(editText) => editText
        // Generate the View based on the Thing:
        case None => {
          val layoutPieces = propsToEditForThing(thing, state).map(EditorPropLayout(_))
          val layoutRows = splitRows(layoutPieces)
          // TODO: need to break this into distinct 12-span rows!
          val propsLayout = s"""{{_instanceEditor:
              |${layoutRows.map(_.layout).mkString}
              |}}
              |""".stripMargin
          QLText(propsLayout)
        }
      }
    }
    
    def instanceEditorForThing(thing:Thing, thingContext:QLContext, params:Option[Seq[QLPhrase]]):Wikitext = {
      implicit val state = thingContext.state
      val editText = editorLayoutForThing(thing, state)
      val parser = new QLParser(editText, thingContext, params)
      parser.process
    }
  
    def fullyApply(mainContext:QLContext, partialContext:QLContext, params:Option[Seq[QLPhrase]]):QValue = {
      applyToIncomingThing(partialContext) { (partialThing, _) =>
        partialThing match {
          case prop:Property[_,_] => {
            applyToIncomingThing(mainContext) { (mainThing, _) =>
              applyToPropAndThing(mainContext, mainThing, partialContext, prop, params)
            }
          }
          
          case thing:ThingState => {
            implicit val state = partialContext.state
            if (thing.ifSet(IsModelProp)) {
              val instances = state.descendants(thing.id, false, true).toSeq.sortBy(_.displayName)
              val wikitexts = instances.map { instance => instanceEditorForThing(instance, instance.thisAsContext(partialContext.request), params) }
              QList.from(wikitexts, ParsedTextType)
            } else {
              WikitextValue(instanceEditorForThing(thing, partialContext, params))
            }
          }
          
          case _ => ErrorValue("The " + displayName + " method can only be used on Properties, Models and Instances")
        } 
      }
    }
  }
  
  lazy val editMethod = new EditMethodBase(EditMethodOID, 
    toProps(
      setName("_edit"),
      PropSummary("Puts an editor for the specified Property into the page"),
      PropDetails("""Sometimes, you want to make it easy to edit a Thing, without having to go into the Editor
          |page. For instance, there may be a single button, or a few fields, that should be more easily editable
          |directly when you are looking at the Thing. That is when you use _edit.
          |
          |Use it like this:
          |    THING -> PROPERTY._edit
          |This means "put an edit control for the PROPERTY on THING right here".
          |
          |There isn't yet a way to say what particular *kind* of edit control is used -- there is a default control
          |depending on PROPERTY. For instance, if it is a Large Text Property, a big resizeable text input will be
          |shown. If it is an Optional Yes or No Property, a trio of Yes/Maybe/No buttons will be displayed. (Later,
          |we will undoubtedly add ways to control this more precisely.)
          |
          |The edit control will display the current contents of PROPERTY when it is shown. Changes take place immediately,
          |with no "save" button or anything like that. (Later, we plan to make a Save button optional, but that still
          |needs to be designed.)
          |
          |If the current user isn't allowed to edit this Thing, _edit instead displays the ordinary, rendered value of
          |the Property. If you want to do something else in this case, use [[_editOrElse._self]] instead.""".stripMargin),
      AppliesToKindProp(Kind.Property)
    )) 
  {
    def cantEditFallback(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      params:Option[Seq[QLPhrase]]):QValue = {
        // This user isn't allowed to edit, so simply render the property in its default form.
        // For more control, user _editOrElse instead.
        prop.qlApply(mainContext, params)    
    }  
  }
  
  lazy val editOrElseMethod = new EditMethodBase(EditOrElseMethodOID, 
    toProps(
      setName("_editOrElse"),
      PropSummary("Like [[_edit._self]], but you can say what to show if the user can't edit this Property"),
      PropDetails("""See [[_edit._self]] for the full details of how edit control works. This is just like that,
          |but with an additional parameter:
          |    THING -> PROPERTY._editOrElse(FALLBACK)
          |If the current user isn't allowed to edit THING, then FALLBACK is produced instead.""".stripMargin),
      AppliesToKindProp(Kind.Property)
    )) 
  {
    def cantEditFallback(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      paramsOpt:Option[Seq[QLPhrase]]):QValue = {
        // This user isn't allowed to edit, so display the fallback
        paramsOpt match {
          case Some(params) if (params.length > 0) => {
            mainContext.parser.get.processPhrase(params(0).ops, mainContext).value
          }
          case _ => WarningValue("_editOrElse requires a parameter")
        }
    }  
  }

  /**
   * This is probably badly factored -- in the long run, I suspect this should actually be a param to _edit instead.
   * But this will do to start.
   * 
   * TODO: this is weirdly incestuous with HtmlRenderer. Think about how the factoring should really work.
   */
  lazy val editAsPicklistMethod = new EditMethodBase(EditAsPickListOID, 
    toProps(
      setName("_editAsPickList"),
      PropSummary("Edits a Tag or Link Set as a Pick List"),
      PropDetails("""This is broadly similar to [[_edit._self]], but displays in a way that is sometimes more useful.
          |
          |To use _editAsPickList, your set must have a Link Model set. This displays all known instances of that Link Model
          |as a checklist, and allows you to decide what is in or out simply by checking things in the list.""".stripMargin),
      AppliesToKindProp(Kind.Property)
    )) 
  {
    // TODO: this is stolen directly from _edit, and should probably be refactored:
    def cantEditFallback(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      params:Option[Seq[QLPhrase]]):QValue = {
        // This user isn't allowed to edit, so simply render the property in its default form.
        // For more control, user _editOrElse instead.
        prop.qlApply(mainContext, params)    
    }  
    
    override def specialization(mainContext:QLContext, mainThing:Thing, 
      partialContext:QLContext, prop:Property[_,_],
      paramsOpt:Option[Seq[QLPhrase]]):Set[RenderSpecialization] = 
    {
      // This is basically saying "if there is one parameter, and it is the token 'withAdd'"
      // TODO: all of this should go behind a better-built parameter wrapper.
      val hasAddOpt = for (
        params <- paramsOpt;
        if (params.length > 0);
        param = params(0);
        QLCall(addName, _, _, _) = param.ops(0);
        if (addName.name.toLowerCase() == "withadd")
          )
        yield true
        
      hasAddOpt.map(_ => Set(PickList, WithAdd)).getOrElse(Set(PickList))
    }
  }
  
  override lazy val props = Seq(
    instanceEditViewProp,
    editMethod,
    editOrElseMethod,
    editAsPicklistMethod
  )
}