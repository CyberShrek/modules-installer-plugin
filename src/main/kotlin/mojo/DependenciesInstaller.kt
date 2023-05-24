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
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder
import org.apache.maven.shared.dependency.graph.DependencyNode
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.annotation.Resources
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// Installs dependencies to WildFly
@Mojo(name = "install-to-wildfly", defaultPhase = LifecyclePhase.INSTALL)
class DependenciesInstaller : AbstractMojo() {

    @Component
    private lateinit var dependencyGraphBuilder: DependencyCollectorBuilder

    @Component
    private lateinit var repositorySystem: RepositorySystem

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(required = true)
    private lateinit var wildflyHome: String
    private lateinit var modulesHome: String

    @Parameter(defaultValue = "")
    private var groupName: String = ""

    @Parameter(defaultValue = "")
    private var configFiles: Set<String> = emptySet()

    @Parameter(defaultValue = "")
    private var extraDependencies: Set<String> = emptySet()

    @Parameter(defaultValue = "false")
    private var resourcesInsteadOfDependencies: Boolean = false

    @Parameter(defaultValue = "false")
    private var isGlobal: Boolean = false

    @Parameter(defaultValue = "MERGE", required = true)
    private lateinit var installMode: Mode
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
            if(installMode == Mode.REPLACE && groupName.isNotBlank())
                File("$modulesHome/$groupName").clearDirectory()
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
                .collectDependencyGraph(
                    DefaultProjectBuildingRequest(session.projectBuildingRequest)
                        .apply { project = session.currentProject }, null
                )
        ).apply {
            configFiles.forEach {
                patchConfig(it, name)
            }
        }
    }

    private fun patchConfig(configName: String, moduleName: String){
        val configFile = File("$wildflyHome/standalone/configuration/${configName.trim()}")
        log.info("Правка файла конфигурации: " + configFile.absolutePath)
        if(!configFile.exists()) throw FileNotFoundException("Файл конфигурации не существует по указанному пути")

        with(configFile.parseDocument()) {
            firstChild!!
                .getOrCreateChild("profile")
                .getOrCreateChild("subsystem", "urn:jboss:domain:ee")
                .getOrCreateChild("global-modules")
                .apply {
                    getOrCreateChild("module", name = moduleName)
                        .also {
                            if(!isGlobal)
                                removeChild(it)
                        }
                }
            configFile.saveDocument(this)
        }
    }

    // Inner class Module takes in a DependencyNode and an optional depth parameter, logs artifact and fetches it via Maven.
    // It initializes name and home properties, resolves home directory, copies artifact file, and adds dependencies as child nodes.
    // It also creates module.xml file, adds resources and dependencies to it, and saves the updated document.
    // All properties are logged. A separator line is added at the end.
    inner class Module(node: DependencyNode, depth: Int = 0){

        private val logIndent = "|   ".repeat(depth)
        private fun log(message: String) = log.info(logIndent + message)

        init {
            log(node.artifact.toString())
            // Fetching the node artifact via Maven repository system
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        val name = ((if (groupName.isNotBlank()) "$groupName." else "") + (JarFile(node.artifact.file)
            .manifest
            ?.mainAttributes
            ?.getValue("Automatic-Module-Name")
            ?: "${node.artifact.groupId}.${node.artifact.artifactId}") +"."+ node.artifact.version.replace('.', '-'))
            .also {
                log("Имя  модуля: $it")
            }

        private val home = "$modulesHome/${name.replace('.', '/')}/main/"
            .also {
                log("Путь модуля: $it")
                // Resolving the home
                with(File(it)) {
                    if (!exists()) mkdirs()
                    else if(installMode == Mode.UPDATE || installMode == Mode.REPLACE)
                        clearDirectory()else {}
                }
                // Copying artifact file from local Maven repository to module home
                Files.copy(
                    node.artifact.file.toPath(),
                    File(it + node.artifact.file.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

        private val resources = File(home).listFiles{ file -> file.isFile && file.name.endsWith(".jar")}

        private val dependencies =
        if(node.children.isNotEmpty()) {
            log("Зависимости: ")
            node.children.map { node -> Module(node, depth + 1) }
        }
        else null

        init{
            val file = File(home + "module.xml")

            val document = if (file.exists())
                file.parseDocument()
            else createEmptyDocument(
                "module",
                xmlns = "urn:jboss:module",
                name  = name
            )

            // Adding resources
            if(resources != null)
                document.fillResources(resources.map { it.name })

            // Adding dependencies
            if (dependencies?.isNotEmpty() == true) {
                // Installs resources Instead Of Dependencies only in the root module
                if (resourcesInsteadOfDependencies && depth == 0)
                    document.fillResources(getAllResourcePaths())
                else
                    document.fillDependencies(dependencies.map { it.name })
            }

            // Adding extra dependencies
            if(extraDependencies.isNotEmpty() && depth == 0)
                document.fillDependencies(extraDependencies)

            file.saveDocument(document)

            log("——————————————————————————————————————————————————————————————————————————")
        }

        // Returns all resources including dependency resources
        private fun getAllResourcePaths(): Set<String> = (
                (resources?.map { it.absolutePath } ?: emptyList())
                        + (dependencies?.map { it.getAllResourcePaths() }?.flatten() ?: emptyList())
                ).toSet()

        private fun Document.fillResources(resourcePaths: Iterable<String>) =
            with(firstChild.getOrCreateChild("resources")){
                resourcePaths.forEach {
                    getOrCreateChild("resource-root", path = it)
                }
            }

        private fun Document.fillDependencies(dependencies: Iterable<String>) =
            with(firstChild.getOrCreateChild("dependencies")){
                dependencies.forEach {
                    getOrCreateChild("module", name = it.trim(), export = true)
                }
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

    private fun Node.getChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (this.nodeName == nodeName
                    && (xmlns == null || attributes.getNamedItem("xmlns")?.nodeValue?.substringBeforeLast(":") == xmlns)
                    && (name == null  || attributes.getNamedItem("name")?.nodeValue == name)
                    && (path == null  || attributes.getNamedItem("path")?.nodeValue == path)
                ) return this
            }
        }
        return null
    }

    private fun Node.createChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null, export: Boolean? = null): Node{
        // Removing last empty nodes
        while (lastChild != null && lastChild.nodeType == Node.TEXT_NODE && lastChild.nodeValue?.trim()?.isEmpty() == true)
            removeChild(lastChild)

        return (if(this is Document) this else ownerDocument).createElement(nodeName).also {
            if(xmlns != null) it.setAttribute("xmlns","${xmlns}:1.0")
            if(name  != null) it.setAttribute("name", name)
            if(path  != null) it.setAttribute("path", path)
            if(export  != null) it.setAttribute("export", export.toString())
            appendChild(it)
        }
    }

    private fun Node.getOrCreateChild(nodeName: String, xmlns: String? = null, name: String? = null, path: String? = null, export: Boolean? = null) =
        getChild(nodeName, xmlns, name, path) ?: createChild(nodeName, xmlns, name, path, export)
}