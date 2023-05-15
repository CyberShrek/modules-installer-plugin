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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


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

    @Parameter(defaultValue = "global")
    private lateinit var group: String

    @Parameter(defaultValue = "main")
    private lateinit var slot: String

    @Parameter(defaultValue = "MERGE", required = true)
    private lateinit var writeMode: WriteMode
    private enum class WriteMode {
        REPLACE,
        UPDATE,
        MERGE
    }

    private val groupHome: String
        get() = "$wildflyHome/modules/$group/"

    override fun execute() {
        try {
            checkIfWildFlyExists()
            if(writeMode == WriteMode.REPLACE)
                File(groupHome).clearDirectory()
            installModules()
        }
        catch (err: Error){
            log.error(err.message)
            throw err
        }
        catch (ex: Exception){
            log.error(ex.message)
            throw ex
        }
    }

    private fun checkIfWildFlyExists() = with(File(wildflyHome)) {
        if(!exists())    throw Error("WildFly doesn't exist: $wildflyHome")
        if(!isDirectory) throw Error("WildFly location is not directory: $wildflyHome")
    }

    private fun installModules() = Module(
        dependencyGraphBuilder
            .buildDependencyGraph(
                DefaultProjectBuildingRequest(session.projectBuildingRequest)
                    .apply { project = session.currentProject }, null)
    )

    inner class Module(node: DependencyNode, depth: Int = 0){
        private val logIndent = "|   ".repeat(depth)
        private fun log(message: String) = log.info(logIndent + message)

        init {
            log(node.artifact.toString())
            // Fetching the node artifact via Maven repository system
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        private val name = (JarFile(node.artifact.file)
            .manifest
            ?.mainAttributes
            ?.getValue("Automatic-Module-Name")
            ?: "${node.artifact.groupId}.${node.artifact.artifactId}").also {
                log("module name: $it")
            }

        private val home = "$groupHome${name.replace('.', '/')}/$slot/"
            .also {
                log("module home: ${it.replace('\\', '/')}")
                // Resolving the home
                with(File(it)) {
                    if (!exists()) mkdirs()
                    else if(writeMode == WriteMode.UPDATE)
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
                    log("dependencies:")
            }.map {
                child -> Module(child, depth + 1)
            }.toSet()

        init{
            // Resolving module.xml
            val moduleFile = File(home + "module.xml")
            val moduleRoot =
                if (moduleFile.exists() && writeMode == WriteMode.MERGE)
                    moduleFile.parseDocument()
                else createEmptyDocument(
                    "module",
                    xmlns = "urn:jboss:module",
                    name  = "global.org.postgresql.jdbc"
                ).firstChild

            with(moduleRoot.getOrCreateChild("resources")){
                File(home).listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".jar"))
                        getOrCreateChild("resource-root", path = file.name)
                }
            }

            if(dependencies.isNotEmpty())
                with(moduleRoot.getOrCreateChild("dependencies")){
                    dependencies.forEach {
                        getOrCreateChild("module", name = it.name)
                    }
                }

            log("------------------------------------------------------------------------")
        }
    }

    private fun createEmptyDocument(tagName: String, xmlns: String? = null, name: String? = null): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            .also { it.createChild(tagName, xmlns, name) }

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

    private fun Node.getChild(tagName: String, xmlns: String? = null, name: String? = null, path: String? = null): Node? {
        val childNodes = this.childNodes
        for (i in 0 until childNodes.length) {
            with(childNodes.item(i)){
                if (nodeName == name
                    && (xmlns == null || attributes.getNamedItem("xmlns")?.nodeValue?.substringBeforeLast(":") == xmlns)
                    && (name == null  || attributes.getNamedItem("name")?.nodeValue == name)
                    && (path == null  || attributes.getNamedItem("path")?.nodeValue == path)
                ) return this
            }
        }
        return null
    }

    private fun Node.createChild(tagName: String, xmlns: String? = null, name: String? = null, path: String? = null): Node{
        // Removing last empty nodes
        while (lastChild != null && lastChild.nodeType == Node.TEXT_NODE && lastChild.nodeValue.trim().isEmpty())
            removeChild(lastChild)

        return ownerDocument.createElement(tagName).also {
            if(xmlns != null) it.setAttribute("xmlns","${xmlns}:1.0")
            if(name  != null) it.setAttribute("name", name)
            if(path  != null) it.setAttribute("name", path)
            appendChild(it)
        }
    }

    private fun Node.getOrCreateChild(tagName: String, xmlns: String? = null, name: String? = null, path: String? = null) =
        getChild(tagName, xmlns, name, path) ?: createChild(tagName, xmlns, name, path)
}