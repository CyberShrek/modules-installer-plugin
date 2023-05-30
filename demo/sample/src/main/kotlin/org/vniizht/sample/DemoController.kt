package org.vniizht.sample

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DemoController {

    // Возвращает небольшое описание экземпляров внедрённых библиотек, чтобы мы могли убедиться, что они действительно внедрены
    @GetMapping
    fun getClassDescriptions() = setOf(
        getClassDescription("org.postgresql", "Driver"),
        getClassDescription("org.apache.poi", "POIDocument"),
        getClassDescription("org.hibernate", "Hibernate"),
        getClassDescription("com.google.common.base", "Defaults"),
        getClassDescription("org.apache.logging.log4j", "Logger")
    )

    private fun getClassDescription(packageName: String,
                                    className:   String): String =
        "$packageName: ${
            with(Package.getPackage(packageName)) {
                if (this == null) "cannot resolve"
                else implementationVersion ?: "no version"
            }
        } ($className ${
            try {
                Class.forName("$packageName.$className")
                "available"
            } catch (e: ClassNotFoundException) {
                "not available"
            }
        })"
}