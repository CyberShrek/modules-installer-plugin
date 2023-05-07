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
import org.intellij.lang.annotations.Language
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
    lateinit var wildflyHome: String

    @Parameter(defaultValue = "global")
    lateinit var group: String

    @Parameter(defaultValue = "main")
    lateinit var slot: String

    override fun execute() {
        try {
            checkIfWildFlyExists()
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

    inner class Module(node: DependencyNode, depth: Int = 0){
        init {
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        fun install(){
            log.info(indent + name)
            copyFileToHome()
            createOrReplaceModuleXml()
            for (dependency in dependencies)
                dependency.install()
        }

        private val file = node.artifact.file
        private val name = JarFile(file)
            .manifest
            ?.mainAttributes
            ?.getValue("Automatic-Module-Name")
            ?: "${node.artifact.groupId}.${node.artifact.artifactId}"

        private val home = "$wildflyHome/modules/$group/${name.replace('.', '/')}/$slot/"
        private val dependencies = node.children.map { child -> Module(child, depth+1) }

        private val indent = "|   ".repeat(depth)

        private val xml: String
            @Language("xml")
            get() = """
             <module xmlns="urn:jboss:module:1.1" name="$name">
                 <resources>
                     ${homeJarFileNames.joinToString("""
                     """) { fileName -> "<resource-root path=$fileName/>" }}
                 </resources>
                 <dependencies>
                     ${dependencies.joinToString("""
                     """) { dependency -> "<module name=\"${dependency.name}\"/>" }}
                 </dependencies>
             </module>
        """.trimIndent()

        private val homeJarFileNames: List<String>
            get() = File(home).listFiles { file -> file.isFile && file.name.endsWith(".jar") }
                ?.map { it.name }
                ?: emptyList()

        private fun copyFileToHome() = Files.copy(
            file.toPath(),
            File(
                File(home).apply { if(!exists()) mkdirs() },
                file.name
            ).toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        private fun createOrReplaceModuleXml() = with(File(home, "module.xml")){
            createNewFile()
            writeText(xml)
        }
    }
}