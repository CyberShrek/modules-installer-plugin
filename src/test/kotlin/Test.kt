import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.util.stream.IntStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.test.Test


class Test {

    private val file = File("standalone.xml")
    private val document = file.parseXml()

    @Test
    fun test() {
        val rootElement = document.getElementsByTagName("root").item(0)

        rootElement.removeEmptyTextNodes()

        val person1 = document.createElement("person")
        person1.setAttribute("name", "John")
        rootElement.appendChild(person1)

        val person2 = document.createElement("person")
        person2.setAttribute("name", "Jane")
        rootElement.appendChild(person2)


//        val subsystemNode = document
//            .getChild("server", "urn:jboss:domain")
//            ?.getChild("profile")
//            ?.getChild("subsystem", "urn:jboss:domain:ee")

//        subsystemNode?.getOrCreateChild("test0", "test")
//
//        subsystemNode?.getOrCreateChild("test1", "test")
//        subsystemNode?.getOrCreateChild("test2", "test")
//        subsystemNode?.getOrCreateChild("test3", "test")
//        subsystemNode?.getOrCreateChild("test4", "test")
//        val modulesNode: Node

        file.saveXml(document)

    }

    private fun Node.getOrCreateChild(name: String, xmlns: String? = null): Node {
        var child = getChild(name, xmlns)
        return if (child != null) child
        else {
            child = ownerDocument.createElement(name)
            if(xmlns != null) child.setAttribute("xmlns","$xmlns:1.0")
            appendChild(child)
            child
        }
    }

    private fun Node.getChild(name: String, xmlns: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (nodeName == name
                    && if(xmlns != null)
                        attributes.getNamedItem("xmlns").nodeValue.substringBeforeLast(":") == xmlns
                    else true)
                    return this
            }
        }
        return null
    }

    private fun Node.removeEmptyTextNodes() {
        for (i in childNodes.length - 1 downTo 0) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.TEXT_NODE && node.nodeValue.trim().isEmpty())
                removeChild(node)
        }
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