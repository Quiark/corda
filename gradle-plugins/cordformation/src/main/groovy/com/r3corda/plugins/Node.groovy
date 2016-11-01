package com.r3corda.plugins

import org.gradle.api.internal.file.AbstractFileCollection
import org.gradle.api.Project
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigRenderOptions

/**
 * Represents a node that will be installed.
 */
class Node {
    static final String JAR_NAME = 'corda.jar'
    static final String DEFAULT_HOST = 'localhost'

    /**
     * Name of the node.
     */
    public String name
    /**
     * A list of advertised services ID strings.
     */
    protected List<String> advertisedServices = []
    /**
     * Set thThe list of cordapps to install to the plugins directory.
     *
     * @note Your app will be installed by default and does not need to be included here.
     */
    protected List<String> cordapps = []

    private String dirName
    private Config config = ConfigFactory.empty()
    //private Map<String, Object> config = new HashMap<String, Object>()
    private File nodeDir
    private Project project

    /**
     * Set the name of the node.
     *
     * @param name The node name.
     */
    void name(String name) {
        this.name = name
        config = config.withValue("myLegalName", ConfigValueFactory.fromAnyRef(name))
    }

    /**
     * Set the directory the node will be installed to relative to the directory specified in Cordform task.
     *
     * @param dirName Subdirectory name for node to be installed to. Must be valid directory name on all OSes.
     */
    void dirName(String dirName) {
        this.dirName = dirName
        config = config.withValue("basedir", ConfigValueFactory.fromAnyRef(dirName))
    }

    /**
     * Set the nearest city to the node.
     *
     * @param nearestCity The name of the nearest city to the node.
     */
    void nearestCity(String nearestCity) {
        config = config.withValue("nearestCity", ConfigValueFactory.fromAnyRef(nearestCity))
    }

    /**
     * Sets whether this node will use HTTPS communication.
     *
     * @param isHttps True if this node uses HTTPS communication.
     */
    void https(Boolean isHttps) {
        config = config.withValue("useHTTPS", ConfigValueFactory.fromAnyRef(isHttps))
    }

    /**
     * Set the artemis port for this node.
     *
     * @param artemisPort The artemis messaging queue port.
     */
    void artemisPort(Integer artemisPort) {
        config = config.withValue("artemisAddress",
                ConfigValueFactory.fromAnyRef("$DEFAULT_HOST:$artemisPort".toString()))
    }

    /**
     * Set the HTTP web server port for this node.
     *
     * @param webPort The web port number for this node.
     */
    void webPort(Integer webPort) {
        config = config.withValue("webAddress",
                ConfigValueFactory.fromAnyRef("$DEFAULT_HOST:$webPort".toString()))
    }

    /**
     * Set the network map address for this node.
     *
     * @warning This should not be directly set unless you know what you are doing. Use the networkMapName in the
     *          Cordform task instead.
     * @param networkMapAddress Network map address.
     */
    void networkMapAddress(String networkMapAddress) {
        config = config.withValue("networkMapAddress",
                ConfigValueFactory.fromAnyRef(networkMapAddress))
    }

    Node(Project project) {
        this.project = project
    }

    /**
     * Install the nodes to the given base directory.
     *
     * @param baseDir The base directory for this node. All other paths are relative to it + this nodes dir name.
     */
    void build(File baseDir) {
        nodeDir = new File(baseDir, dirName)
        installCordaJAR()
        installBuiltPlugin()
        installCordapps()
        installDependencies()
        installConfig()
    }

    /**
     * Get the artemis address for this node.
     *
     * @return This node's artemis address.
     */
    String getArtemisAddress() {
        return config.getString("artemisAddress")
    }

    /**
     * Installs the corda fat JAR to the node directory.
     */
    private void installCordaJAR() {
        def cordaJar = verifyAndGetCordaJar()
        project.copy {
            from cordaJar
            into nodeDir
            rename cordaJar.name, JAR_NAME
        }
    }

    /**
     * Installs this project's cordapp to this directory.
     */
    private void installBuiltPlugin() {
        def pluginsDir = new File(nodeDir, "plugins")
        project.copy {
            from project.jar
            into pluginsDir
        }
    }

    /**
     * Installs other cordapps to this node's plugins directory.
     */
    private void installCordapps() {
        def pluginsDir = new File(nodeDir, "plugins")
        def cordapps = getCordappList()
        project.copy {
            from cordapps
            into pluginsDir
        }
    }

    /**
     * Installs other dependencies to this node's dependencies directory.
     */
    private void installDependencies() {
        def cordaJar = verifyAndGetCordaJar()
        def cordappList = getCordappList()
        def depsDir = new File(nodeDir, "dependencies")
        def appDeps = project.configurations.runtime.filter { it != cordaJar && !cordappList.contains(it) }
        project.copy {
            from appDeps
            into depsDir
        }
    }

    /**
     * Installs the configuration file to this node's directory and detokenises it.
     */
    private void installConfig() {
        // Adding required default values
        config = config.withValue('extraAdvertisedServiceIds',
                ConfigValueFactory.fromAnyRef(advertisedServices.join(',')))
        def configFileText = config.root().render(new ConfigRenderOptions(false, false, true, false)).split("\n").toList()

        // Need to write a temporary file first to use the project.copy, which resolves directories correctly.
        def tmpDir = new File(project.buildDir, "tmp")
        def tmpConfFile = new File(tmpDir, 'node.conf')
        Files.write(tmpConfFile.toPath(), configFileText, StandardCharsets.UTF_8)

        project.copy {
            from tmpConfFile
            into nodeDir
        }
    }

    /**
     * Find the corda JAR amongst the dependencies.
     *
     * @return A file representing the Corda JAR.
     */
    private File verifyAndGetCordaJar() {
        def maybeCordaJAR = project.configurations.runtime.filter { it.toString().contains("corda-${project.corda_version}.jar")}
        if(maybeCordaJAR.size() == 0) {
            throw new RuntimeException("No Corda Capsule JAR found. Have you deployed the Corda project to Maven?")
        } else {
            def cordaJar = maybeCordaJAR.getSingleFile()
            assert(cordaJar.isFile())
            return cordaJar
        }
    }

    /**
     * Gets a list of cordapps based on what dependent cordapps were specified.
     *
     * @return List of this node's cordapps.
     */
    private AbstractFileCollection getCordappList() {
        def cordaJar = verifyAndGetCordaJar()
        return project.configurations.runtime.filter {
            def jarName = it.name.split('-').first()
            return (it != cordaJar) && cordapps.contains(jarName)
        }
    }
}
