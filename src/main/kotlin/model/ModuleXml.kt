package model

import javax.xml.bind.annotation.XmlAttribute
import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlElementWrapper
import javax.xml.bind.annotation.XmlRootElement

@XmlRootElement(name = "module")
data class ModuleXml(
    @XmlAttribute val xmlns: String? = "urn:jboss:module:1.1",
    @XmlAttribute val name: String? = null,

    @XmlElementWrapper(name = "resource-root")
    @field:XmlElement(name="resource") val resources: Set<ResourceXml>? = null,

    @XmlElementWrapper
    @field:XmlElement(name="dependency") val dependencies: Set<ModuleXml>? = null
)

data class ResourceXml(
    @XmlAttribute val path: String? = null)