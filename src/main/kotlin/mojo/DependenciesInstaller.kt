package mojo

import model.ModuleXml
import model.ResourceXml
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
import java.io.File
import java.lang.Boolean
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller


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
            if(writeMode == WriteMode.REPLACE) clearDirectory(File(groupHome))
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

    private fun clearDirectory(directory: File) {
        directory.listFiles()?.forEach {
            if (it.isDirectory) clearDirectory(it) else it.delete()
        }
    }

    private inline fun <reified T>serializeXml(file: File, xmlObj: T) =
        with(JAXBContext.newInstance(T::class.java).createMarshaller()){
            setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE)
            marshal(xmlObj, file)
        }

    private inline fun <reified T>deserializeXml(file: File): T =
        JAXBContext.newInstance(T::class.java)
            .createUnmarshaller()
            .unmarshal(file) as T


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
                        clearDirectory(this)else {}
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
            // Creating module.xml
            val moduleXmlFile = File(home + "module.xml")
            val oldModuleXml =
                if (moduleXmlFile.exists() && writeMode == WriteMode.MERGE)
                    deserializeXml<ModuleXml>(moduleXmlFile)
                else null

            serializeXml(moduleXmlFile,
                ModuleXml(
                    name = name,
                    resources = File(home).listFiles { file -> file.isFile && file.name.endsWith(".jar") }
                        ?.map { ResourceXml(path = it.name) }?.toSet()
                        ?.plus(oldModuleXml?.resources ?: emptySet())
                        ?: emptySet(),
                    dependencies = dependencies.map { ModuleXml(name = it.name) }.toSet()
                        .plus(oldModuleXml?.dependencies ?: emptySet())
                )
            )
            log("------------------------------------------------------------------------")
        }
    }
}