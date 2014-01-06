package querki

import models.Property
import models.system.QLText
import models.system.OIDs.sysId

import querki.ecology._

import modules.ModuleIds

package object conventions {
  object MOIDs extends ModuleIds(15) {
    // Old OIDs, moved to here:
    val PropSummaryOID = sysId(85)
    val PropDetailsOID = sysId(86)
    
    val PropDescriptionOID = moid(1)    
  }
  
  trait Conventions extends EcologyInterface {  
    def PropSummary:Property[QLText,String]
    def PropDetails:Property[QLText,String]
  }
}