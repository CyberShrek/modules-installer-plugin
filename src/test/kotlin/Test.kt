import model.ModuleXml
import model.ResourceXml
import java.io.File
import java.lang.Boolean
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller
import kotlin.test.Test


class Test {

    @Test
    fun test() {
        val module = ModuleXml(name = "test", resources = setOf(ResourceXml("xcvcxvvvcx"), ResourceXml("12322323")))
//        module.resources = setOf(ResourceXml("xcvcxvvvcx"), ResourceXml("12322323"))


        val context = JAXBContext.newInstance(ModuleXml::class.java)
        val mar: Marshaller = context.createMarshaller()
        mar.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE)
        mar.marshal(module, File("./module.xml"))
    }
}