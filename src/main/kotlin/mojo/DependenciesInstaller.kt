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

        val name = with(node.artifact){
            JarFile(file).manifest?.mainAttributes?.getValue("Automatic-Module-Name")
                ?: "$groupId.$artifactId"
        }
        val home = "$wildflyHome/modules/${name.replace('.', '/')}/$group/$slot/"
        val dependencies = node.children.map { child -> Module(child, depth+1) }

        @Language("xml")
        private val xml = """
             <module xmlns="urn:jboss:module:1.1" name="$name">
                 <resources>
                     <resource-root path="${node.artifact.file.name}"/>
                 </resources>
                 <dependencies>
                     ${dependencies.joinToString("""
                     """) { dependency -> "<module name=\"${dependency.name}\"/>" }}
                 </dependencies>
             </module>
        """.trimIndent()

        fun install(logDepth: Int = 0){
            log.info(
                if(logDepth == 0) "${name} modules installation:"
                else xml//"|   ".repeat(logDepth) + name

            )
            for (dependency in dependencies)
                dependency.install(logDepth + 1)
        }

        fun copyFileToPath(path: String) {
            val newFile = File(path)
            if (!newFile.exists()) {
                newFile.createNewFile()
            }
//            Files.copy(no.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

    }
}