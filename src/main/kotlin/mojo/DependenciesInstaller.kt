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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile


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
                clearDirectory(groupHome)
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
    ).install()

    private fun clearDirectory(directoryPath: String) = clearDirectory(File(directoryPath))
    private fun clearDirectory(directory: File) {
        directory.listFiles()?.forEach {
            if (it.isDirectory) clearDirectory(it) else it.delete()
        }
    }

    inner class Module(node: DependencyNode){
        init {
            // Fetching the node artifact via Maven repository system
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        private val name = JarFile(node.artifact.file)
            .manifest
            ?.mainAttributes
            ?.getValue("Automatic-Module-Name")
            ?: "${node.artifact.groupId}.${node.artifact.artifactId}"

        private val home = "$groupHome${name.replace('.', '/')}/$slot/".also {
            // Resolving the home
            with(File(it)) {
                if (!exists()) mkdirs()
                else if(writeMode == WriteMode.UPDATE)
                    clearDirectory(this) else {}
            }
            // Copying artifact file from local Maven repository to actual module home
            Files.copy(
                node.artifact.file.toPath(),
                File(it + node.artifact.file.name).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        private val dependencies = node.children.map { child -> Module(child) }.toSet()

        fun install(logDepth: Int = 0){
            log.info("|   ".repeat(logDepth) + name)
            if(writeMode == WriteMode.UPDATE)
                clearDirectory(home)

            addOrMergeModuleXml()
            for (dependency in dependencies)
                dependency.install(logDepth + 1)
        }

        private fun createModuleXml() = ModuleXml(
            name = name,
            resources = File(home).listFiles { file -> file.isFile && file.name.endsWith(".jar") }
                ?.map { ResourceXml(path = it.name) }?.toSet()
                ?: emptySet(),
            dependencies = dependencies.map { ModuleXml(xmlns = null, name = it.name) }.toSet()
        )

        private fun addOrMergeModuleXml(xml: ModuleXml = createModuleXml()) {
            xml.dependencies
        }
    }
}