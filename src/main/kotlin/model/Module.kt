package model

import org.intellij.lang.annotations.Language

data class Module(
    val name: String,
    val resource: String,
    val dependencies: List<String>
){
    @Language("xml")
    val xml = """
             <module xmlns="urn:jboss:module:1.1" name="$name">
                 <resources>
                     <resource-root path="postgresql-42.6.0.jar"/>
                 </resources>

                 <dependencies>
                     <module name="global.org.checkerframework.qualddd"/>
                 </dependencies>
             </module>
    """.trimIndent()
}