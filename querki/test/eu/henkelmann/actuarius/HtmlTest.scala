package eu.henkelmann.actuarius

import querki.test._

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class HtmlTest extends QuerkiTests with Transformer {
  "Simple, legal XHTML fragments" should {
    "parse cleanly" in {
      apply("Hello <div>there</div>") should equal ("""<p>Hello <div>there</div></p>
        |""".stripReturns)
      apply("Hello <span>there</span>") should equal ("""<p>Hello <span>there</span></p>
        |""".stripReturns)
    }    
  }
  
  // This is a major difference from Actuarius, which has an elaborate system for managing "XML blocks"
  // if you put the XML at the beginning of the line. It interacts *very* poorly with the rest of QText,
  // so I've simply disabled it. Thanks to Darker for the test example.
  "HTML blocks" should {
    "just be handled like any other XML" in {
      apply("""<div class="effects-box">

Foo foo foo

Foo foo

</div>""".stripReturns) should equal ("""<p><div class="effects-box"></p>
<p>Foo foo foo</p>
<p>Foo foo</p>
<p></div></p>
    |""".stripReturns)
    }
  }
  
  "Legal class attributes" should {
    "parse cleanly" in {
      apply("""Hello <div class="class-1 class_2">there</div>""") should equal ("""<p>Hello <div class="class-1 class_2">there</div></p>
        |""".stripReturns)
      apply("""Hello <span class="class-1 class_2">there</span>""") should equal ("""<p>Hello <span class="class-1 class_2">there</span></p>
        |""".stripReturns)
    }    
  }
  
  "Legal id attributes" should {
    "parse cleanly" in {
      apply("""Hello <div id="foo">there</div>""") should equal ("""<p>Hello <div id="foo">there</div></p>
        |""".stripReturns)
    }    
  }
  
  "Illegal Tags" should {
    "be escaped and passed through" in {
      apply("Hello <script>there</script>") should equal ("""<p>Hello &lt;script&gt;there&lt;/script&gt;</p>
        |""".stripReturns)      
    }
  }
  
  "Illegal attributes" should {
    "be escaped and passed through" in {
      apply("""Hello <div style="something">there</div>""") should equal ("""<p>Hello &lt;div style=&quot;something&quot;&gt;there</div></p>
        |""".stripReturns)      
    }
  }
  
  "Illegal attribute values" should {
    "be escaped and passed through" in {
      apply("""Hello <div class="javascript:stuff">there</div>""") should equal ("""<p>Hello &lt;div class=&quot;javascript:stuff&quot;&gt;there</div></p>
        |""".stripReturns)      
    }    
  }
}