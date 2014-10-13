package querki.client

import scala.concurrent.Future

import scala.scalajs.js
import org.scalajs.jquery.JQueryDeferred

import upickle._
import autowire._

import querki.globals._

trait TestClientRouter {
  
  def commStub:querki.test.ApiCommStub
  
  import autowire._
  trait AutowireHandler extends autowire.Server[String, upickle.Reader, upickle.Writer] {
    def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
    def write[Result: upickle.Writer](r: Result) = upickle.write(r)
    
    def handle(request:Core.Request[String]):Future[String]
  }
  case class HandlerRecord[T](handler:AutowireHandler)(implicit tag:scala.reflect.ClassTag[T]) {
    def handle(request:Core.Request[String]):Future[String] = {
      handler.handle(request)
    }
  }
  var handlers = Map.empty[Seq[String], HandlerRecord[_]]
  def registerApiHandler[T](methods:String*)(handler:AutowireHandler)(implicit tag:scala.reflect.ClassTag[T]) = {
    val packageAndTrait = tag.runtimeClass.getName().split("\\.")
    val splitLocal = packageAndTrait.flatMap(_.split("\\$")).toSeq

    methods.foreach(method => handlers += ((splitLocal :+ method) -> HandlerRecord[T](handler)))
  }
  def genericApiHandler(request:Core.Request[String]):Future[String] = {
    handlers.get(request.path) match {
      case Some(handlerRecord) => handlerRecord.handle(request)
      case None => throw new Exception(s"Couldn't find handler for trait ${request.path}")
    }
  }
  

  def rawEntryPoint0(name:String)() = {
    lit(
      url = s"/test/$name"
    )
  }
  def entryPoint0(name:String)(userName:String, spaceId:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name"
    )
  }
  def entryPoint1(name:String)(userName:String, spaceId:String, p1:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name/$p1"
    )
  }
  def ajaxEntryPoint1(name:String, handler:(String => Future[String]))(userName:String, spaceId:String, p1:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name/$p1",
      
      // This is returning a JQueryDeferred, essentially.
      // TODO: make this pluggable!
      ajax = { () =>
        lit(
          done = { (cb:js.Function3[String, String, JQueryDeferred, Any]) =>
            // Note that we actually call the callback, and thus fulfill the Future in PlayAjax, synchronously.
            // This is an inaccuracy that could lead to us missing some bugs, but does make the testing more
            // deterministic.
            // TODO: enhance the framework to fire the callbacks asynchronously. We'll have to make sure the
            // Javascript framework doesn't exit prematurely, though, and the test will have to wait for results.
            handler(p1).map { result => cb(result, "", lit().asInstanceOf[JQueryDeferred]) }
          },
          fail = { (cb:js.Function3[JQueryDeferred, String, String, Any]) =>
            // We don't do anything here -- we're not failing for now.
            // TODO: we should do some fail tests!
          }
        ) 
      }
    )
  }
  
  def setupStandardEntryPoints() = {
    def controllers = commStub.controllers
    
    // Entry points referenced in the MenuBar, so need to be present in essentially every Page:
    controllers.Application.createProperty = { entryPoint0("createProperty") _ }
    controllers.Application.editThing = { entryPoint1("editThing") _ }
    controllers.Application.sharing = { entryPoint0("sharing") _ }
    controllers.Application.showAdvancedCommands = { entryPoint1("showAdvancedCommands") _ }
    controllers.Application.thing = { entryPoint1("thing") _ }
    controllers.Application.viewThing = { entryPoint1("viewThing") _ }
    
    controllers.ExploreController.showExplorer = { entryPoint1("showExplorer") _ }
    
    controllers.AdminController.manageUsers = { rawEntryPoint0("manageUsers") _ }
    controllers.AdminController.showSpaceStatus = { rawEntryPoint0("showSpaceStatus") _ }
    controllers.AdminController.sendSystemMessage = { rawEntryPoint0("sendSystemMessage") _ }
    
    controllers.TOSController.showTOS = { rawEntryPoint0("showTOS") _ }
    
    controllers.ClientController.apiRequest = { ajaxEntryPoint1("apiRequest", { pickledRequest =>
      val request = read[Core.Request[String]](pickledRequest)
      genericApiHandler(request)
    }) _ }
  }

}