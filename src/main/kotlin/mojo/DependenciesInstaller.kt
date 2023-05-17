package mojo

import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.DefaultProjectBuildingRequest
import org.apache.maven.repository.RepositorySystem
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder
import org.apache.maven.shared.dependency.graph.DependencyNode
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// Installs dependencies to WildFly
@Mojo(name = "install-to-wildfly", defaultPhase = LifecyclePhase.INSTALL)
class DependenciesInstaller : AbstractMojo() {

    @Component
    private lateinit var dependencyGraphBuilder: DependencyGraphBuilder

    @Component
    private lateinit var repositorySystem: RepositorySystem

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(required = true)
    private lateinit var wildflyHome: String
    private lateinit var modulesHome: String

    @Parameter(defaultValue = "global", required = true)
    private lateinit var group: String

    @Parameter(defaultValue = "main", required = true)
    private lateinit var slot: String

    @Parameter(defaultValue = "standalone", required = true)
    private lateinit var targetXmlConfigs: Array<String>

    @Parameter(defaultValue = "MERGE", required = true)
    private lateinit var mode: Mode
    private enum class Mode {
        REPLACE,
        UPDATE,
        MERGE
    }



    override fun execute() {
        wildflyHome = wildflyHome.replace('\\', '/').removeSuffix("/")
        modulesHome = "$wildflyHome/modules"
        try {
            checkIfWildFlyExists()
            if(mode == Mode.REPLACE)
                File(modulesHome + group).clearDirectory()
            installModules()
        }
        catch (ex: Exception){
            log.error(ex.message)
            throw ex
        }
    }

    private fun checkIfWildFlyExists() = with(File(wildflyHome)) {
        if(!exists())    throw FileNotFoundException("WildFly не существует по указанному пути: $wildflyHome")
        if(!isDirectory) throw FileSystemException("Указанный путь к WildFly должен являться директорией: $wildflyHome")
    }

    private fun installModules() {
        log.info("Получение и установка модулей:")
        Module(
            dependencyGraphBuilder
                .buildDependencyGraph(
                    DefaultProjectBuildingRequest(session.projectBuildingRequest)
                        .apply { project = session.currentProject }, null
                )
        ).also {
            with(it.getGraphNames()) {
                targetXmlConfigs.forEach { configName -> patchConfig(configName, this) }
            }
        }
    }

    private fun patchConfig(configName: String, moduleGraphNames: List<String>){
        val configFile = File("$wildflyHome/standalone/configuration/${configName.trim()}.xml")
        log.info("Правка файла конфигурации: " + configFile.absolutePath)
        if(!configFile.exists()) throw FileNotFoundException("Файл конфигурации не существует по указанному пути")

        with(configFile.parseDocument()
            .firstChild!!
            .getOrCreateChild("profile")
            .getOrCreateChild("subsystem", "urn:jboss:domain:ee")
            .getOrCreateChild("global-modules"))
        {
            if(mode == Mode.REPLACE) while (firstChild != null) removeChild(lastChild)
            moduleGraphNames.forEach { getOrCreateChild("module", name = it, slot = slot) }
            configFile.saveDocument(this.ownerDocument)
        }
    }

    // Inner class Module takes in a DependencyNode and an optional depth parameter, logs artifact and fetches it via Maven.
    // It initializes name and home properties, resolves home directory, copies artifact file, and adds dependencies as child nodes.
    // It also creates module.xml file, adds resources and dependencies to it, and saves the updated document.
    // All properties are logged. A separator line is added at the end.
    inner class Module(node: DependencyNode, depth: Int = 0){

        // Returns every module name including itself and dependencies
        fun getGraphNames(): List<String> = listOf(name) + dependencies.map { it.getGraphNames() }.flatten()

        private val logIndent = "|   ".repeat(depth)
        private fun log(message: String) = log.info(logIndent + message)

        init {
            log(node.artifact.toString())
            // Fetching the node artifact via Maven repository system
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        private val name = ("$group." + (JarFile(node.artifact.file)
            .manifest
            ?.mainAttributes
            ?.getValue("Automatic-Module-Name")
            ?: "${node.artifact.groupId}.${node.artifact.artifactId}")).also {
                log("Имя  модуля: $it")
            }

        private val home = "$modulesHome${name.replace('.', '/')}/$slot/"
            .also {
                log("Путь модуля: $it")
                // Resolving the home
                with(File(it)) {
                    if (!exists()) mkdirs()
                    else if(mode == Mode.UPDATE)
                        clearDirectory()else {}
                }
                // Copying artifact file from local Maven repository to module home
                Files.copy(
                    node.artifact.file.toPath(),
                    File(it + node.artifact.file.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

        private val dependencies = node.children
            .also {
                if(it.isNotEmpty())
                    log("Зависимости:")
            }.map {
                Module(it, depth + 1)
            }.toSet()

        init{
            // Fetching module.xml
            val moduleFile = File(home + "module.xml")
            val moduleDocument =
                if (moduleFile.exists() && mode == Mode.MERGE)
                    moduleFile.parseDocument()
                else createEmptyDocument(
                    "module",
                    xmlns = "urn:jboss:module",
                    name  = name
                )

            // Adding resources
            with(moduleDocument.firstChild.getOrCreateChild("resources")){
                File(home).listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".jar"))
                        getOrCreateChild("resource-root", path = file.name)
                }
            }

            // Adding dependencies
            if(dependencies.isNotEmpty())
                with(moduleDocument.firstChild.getOrCreateChild("dependencies")){
                    dependencies.forEach {
                        getOrCreateChild("module", name = it.name)
                    }
                }

            moduleFile.saveDocument(moduleDocument)

            log("------------------------------------------------------------------------")
        }
    }

    private fun createEmptyDocument(tagName: String, xmlns: String? = null, name: String? = null): Document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .newDocument().also { it.createChild(tagName, xmlns, name) }

    private fun File.parseDocument(): Document =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(this)

    private fun File.saveDocument(doc: Document) = with(TransformerFactory.newInstance().newTransformer()) {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transform(DOMSource(doc), StreamResult(this@saveDocument))
    }

    private fun File.clearDirectory() {
        listFiles()?.forEach {
            if (it.isDirectory) it.clearDirectory()
            it.delete()
        }
    }

    private fun Node.getChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null, slot: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (this.nodeName == nodeName
                    && (xmlns == null || attributes.getNamedItem("xmlns")?.nodeValue?.substringBeforeLast(":") == xmlns)
                    && (name == null  || attributes.getNamedItem("name")?.nodeValue == name)
                    && (path == null  || attributes.getNamedItem("path")?.nodeValue == path)
                    && (slot == null  || attributes.getNamedItem("slot")?.nodeValue == slot)
                ) return this
            }
        }
        return null
    }

    private fun Node.createChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null, slot: String? = null): Node{
        // Removing last empty nodes
        while (lastChild != null && lastChild.nodeType == Node.TEXT_NODE && lastChild.nodeValue?.trim()?.isEmpty() == true)
            removeChild(lastChild)

        return (if(this is Document) this else ownerDocument).createElement(nodeName).also {
            if(xmlns != null) it.setAttribute("xmlns","${xmlns}:1.0")
            if(name  != null) it.setAttribute("name", name)
            if(path  != null) it.setAttribute("path", path)
            if(slot  != null) it.setAttribute("slot", slot)
            appendChild(it)
        }
    }

    private fun Node.getOrCreateChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null, slot: String? = null) =
        getChild(nodeName, xmlns, name, path, slot)
            ?: createChild(nodeName, xmlns, name, path, slot)
}