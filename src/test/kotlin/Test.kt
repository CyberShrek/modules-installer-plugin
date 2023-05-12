
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.test.Test


class Test {

    @Test
    fun test() {
        val file = File("standalone.xml")
        val doc = parseXmlFile(file)

        val subsystemNode = doc
            .getChild("server", "urn:jboss:domain")
            ?.getChild("profile")
            ?.getChild("subsystem", "urn:jboss:domain:ee")

        val modulesNode: Node

        if(subsystemNode?.getChild("global-modules") == null)


        println(
            doc.getChild("server", "urn:jboss:domain")
                ?.getChild("profile")
                ?.getChild("subsystem", "urn:jboss:domain:ee")
                ?.baseURI)
    }

    private fun Node.getChild(name: String, xmlns: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (nodeName == name
                    && if(xmlns != null) attributes.getNamedItem("xmlns").nodeValue.substringBeforeLast(":") == xmlns
                    else true)
                    return this
            }
        }
        return null
    }

    fun updateTestValueInStandaloneXML() {


//        val testNode = findElementByTagName(
//            doc.getElementsByTagName( "server"),
//            "test",
//            "urn:jboss:domain:ee:5.0"
//        )
//        if (testNode != null) {
//            val oldValue = testNode.textContent
//            val newValue = "$oldValue hello"
//            testNode.textContent = newValue
//            saveXmlFile(doc, file)
//        }
    }


    fun parseXmlFile(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        return builder.parse(file)
    }

    fun saveXmlFile(doc: Document, file: File) {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        val source = DOMSource(doc)
        val result = StreamResult(file)
        transformer.transform(source, result)
    }
}