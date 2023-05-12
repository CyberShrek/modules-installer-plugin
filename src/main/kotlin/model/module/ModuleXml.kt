package model.module

import javax.xml.bind.annotation.*

@XmlRootElement(name = "module")
data class ModuleXml(
    @XmlAttribute val name: String? = null,

    @XmlElementWrapper(name = "resource-root")
    @field:XmlElement(name="resource") val resources: Set<ResourceXml>? = null,

    @XmlElementWrapper
    @field:XmlElement(name="dependency") val dependencies: Set<ModuleXml>? = null
)

data class ResourceXml(
    @XmlAttribute val path: String? = null)