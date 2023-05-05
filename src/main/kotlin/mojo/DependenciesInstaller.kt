package mojo

import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.DefaultProjectBuildingRequest
import org.apache.maven.project.ProjectBuildingRequest
import org.apache.maven.repository.RepositorySystem
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder
import org.apache.maven.shared.dependency.graph.DependencyNode
import org.intellij.lang.annotations.Language

import java.io.File


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

    @Parameter(defaultValue = "main")
    lateinit var slot: String

    override fun execute() {
        try {
            checkIfWildFlyExists()
            installModules(buildDependencyGraph())
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

    private fun buildDependencyGraph(): DependencyNode {
        val buildingRequest: ProjectBuildingRequest = DefaultProjectBuildingRequest(session.projectBuildingRequest)
        buildingRequest.project = session.currentProject
        return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null)
    }

    private fun checkIfWildFlyExists() = with(File(wildflyHome)) {
        if(!exists())    throw Error("WildFly doesn't exist: $wildflyHome")
        if(!isDirectory) throw Error("WildFly location is not directory: $wildflyHome")
    }

    private fun installModules(node: DependencyNode, depth: Int = 0) {
        if (depth == 0)
            log.info(node.toNodeString() + " modules installation:")
        else {
            resolveModule(node)
            log.info("${"|   ".repeat(depth)}${node.artifact.file.name}")

//            installModule(node)
        }
        for (child in node.children)
            installModules(child, depth + 1)
    }

    private fun resolveModule(node: DependencyNode) {
        val request = ArtifactResolutionRequest()
        request.artifact = node.artifact
        repositorySystem.resolve(request)
    }

    private fun installModule(node: DependencyNode) {
        fun createModuleName(node: DependencyNode) = with(node.artifact){"$groupId.$artifactId.${version.replace('.', '-')}"}
        val name = createModuleName(node)
        val resource = node.artifact.file?.absolutePath
        val dependencies = node.children.map { child -> createModuleName(child) }

    }

    inner class Module(node: DependencyNode, depth: Int = 0){
        init {
            repositorySystem.resolve(ArtifactResolutionRequest().apply { artifact = node.artifact })
        }

        val name = with(node.artifact){"$groupId.$artifactId.${version.replace('.', '-')}"}
        val absolutePath = node.artifact.file?.absolutePath
        val dependencies = node.children.map { child -> Module(child) }

        @Language("xml")
        private val xml = """
             <module xmlns="urn:jboss:module:1.1" name="$name">
                 <resources>
                     <resource-root path="postgresql-42.6.0.jar"/>
                 </resources>

                 <dependencies>
                     <module name="global.org.checkerframework.qualddd"/>
                 </dependencies>
             </module>
        """.trimIndent()

        fun install(){

        }
    }
}