import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.test.Test


class Test {

    private val file = File("test.xml")
    private val document = createEmptyDocument("server")

    @Test
    fun test() {

        val globalModulesNode = document
            .firstChild!!
            .getOrCreateChild("profile")
            .getOrCreateChild("subsystem", "urn:jboss:domain:ee")
            .getOrCreateChild("global-modules")

        globalModulesNode.getOrCreateChild("module", name = "dcdcdcd")
        globalModulesNode.getOrCreateChild("module", name = "tttttt")
        globalModulesNode.getOrCreateChild("module", name = "dc")
        globalModulesNode.getOrCreateChild("module", name = "gthhhh")
        globalModulesNode.getOrCreateChild("module", name = "3rrrr43r4r4r")

        file.saveXml(document)
    }
    private fun createEmptyDocument(rootName: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            .also { it.appendChild(it.createElement(rootName)) }

    private fun Node.getOrCreateChild(tagName: String,
                                      xmlns: String? = null,
                                      name: String? = null): Node {
        var child = getChild(tagName, xmlns, name)
        return if (child != null) child
        else {
            // Removing last empty nodes
            while (lastChild != null && lastChild.nodeType == Node.TEXT_NODE && lastChild.nodeValue.trim().isEmpty())
                removeChild(lastChild)

            child = ownerDocument.createElement(tagName)
            if(xmlns != null) child.setAttribute("xmlns","$xmlns:1.0")
            if(name != null) child.setAttribute("name",name)
            appendChild(child)
            child
        }
    }

    private fun Node.getChild(tagName: String,
                              xmlns: String? = null,
                              name: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (nodeName == tagName
                    && (xmlns == null || attributes.getNamedItem("xmlns")?.nodeValue?.substringBeforeLast(":") == xmlns)
                    && (name == null || attributes.getNamedItem("name")?.nodeValue == name)
                    ) return this
            }
        }
        return null
    }

    private fun File.parseXml(): Document =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(this)

    private fun File.saveXml(doc: Document) = with(TransformerFactory.newInstance().newTransformer()) {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transform(DOMSource(doc), StreamResult(this@saveXml))
    }
}