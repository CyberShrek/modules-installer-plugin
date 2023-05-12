package model.server

import javax.xml.bind.Element
import javax.xml.bind.annotation.XmlAnyElement
import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlRootElement

@XmlRootElement(name = "server")
data class ServerXml(
    @XmlElement
    var profile: Profile? = null,

    @XmlAnyElement(lax = true) val otherElements: List<Element>? = null
)

data class Profile(
    @field:XmlElement
    val subsystem: Subsystem? = null,

    @XmlAnyElement(lax = true) val otherElements: List<Element>? = null
)

data class Subsystem(
    @XmlElement(name = "test")
    var test: String? = "",

    @XmlAnyElement(lax = true) val otherElements: List<Element>? = null
)