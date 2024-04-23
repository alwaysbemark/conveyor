package hydraulic.conveyor.gradle

import dev.hydraulic.types.machines.Machine
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.openjfx.gradle.JavaFXOptions
import java.io.File
import java.util.*

/**
 * A base class for tasks that work with generated Conveyor configuration.
 */
@Suppress("LeakingThis")
abstract class ConveyorConfigTask(
    machineConfigs: Map<String, Configuration>
) : DefaultTask() {
    @get:Input
    abstract val buildDirectory: Property<String>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val projectVersion: Property<Any>

    @get:Input
    abstract val projectGroup: Property<Any>

    @get:Input
    @get:Optional   // Might be set via a Compose plugin setting instead of from application plugin.
    abstract val mainClass: Property<String>

    @get:Input
    abstract val applicationDefaultJvmArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val jvmLanguageVersion: Property<JavaLanguageVersion>

    @get:Input
    @get:Optional
    abstract val jvmVendorValue: Property<String>

    @get:Input
    abstract val rootProjectDir: Property<File>

    @get:Input
    @get:Optional
    abstract val javafxVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val javafxModules: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val composeMainClass: Property<String>

    @get:Input
    @get:Optional
    abstract val composeJvmArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val composePackageName: Property<String>

    @get:Input
    @get:Optional
    abstract val composeDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val composeVendor: Property<String>

    @get:Input
    @get:Optional
    abstract val composeAppResourcesRootDir: Property<File>

    @get:Input
    abstract val appJar: Property<File>


    @get:Input
    abstract val runtimeClasspath: ListProperty<String>

    @get:Input
    abstract val commonFiles: ListProperty<String>

    @get:Input
    abstract val expandedConfigs: MapProperty<String, SortedSet<String>>

    // Can't use type JavaFXOptions here because Gradle can't decorate the class.
    private val javafx: Boolean

    init {
        buildDirectory.convention(project.layout.buildDirectory.get().toString())
        projectName.convention(project.name)
        projectVersion.convention(project.version)
        projectGroup.convention(project.group)

        val javafxExtension = project.extensions.findByName("javafx") as? JavaFXOptions
        javafx = javafxExtension != null
        if (javafxExtension != null) {
            javafxVersion.set(javafxExtension.version)
            javafxModules.set(javafxExtension.modules)
        }

        val appExtension = project.extensions.findByName("application") as? JavaApplication
        if (appExtension != null) {
            mainClass.set(appExtension.mainClass)
            applicationDefaultJvmArgs.set(appExtension.applicationDefaultJvmArgs)
        }

        val javaExtension = project.extensions.findByName("java") as? JavaPluginExtension
        if (javaExtension != null) {
            jvmLanguageVersion.set(javaExtension.toolchain.languageVersion.orNull)
            jvmVendorValue.set(javaExtension.toolchain.vendor.get().toString())
        }

        rootProjectDir.set(project.rootProject.rootDir)

        try {
            val composeExt: ComposeExtension? = project.extensions.findByName("compose") as? ComposeExtension
            val desktopExt: DesktopExtension? = composeExt?.extensions?.findByName("desktop") as? DesktopExtension
            if (desktopExt != null) {
                val app = desktopExt.application
                composeMainClass.set(app.mainClass)
                composeJvmArgs.set(app.jvmArgs)
                val dist = app.nativeDistributions
                composePackageName.set(dist.packageName)
                composeDescription.set(dist.description)
                composeVendor.set(dist.vendor)
                composeAppResourcesRootDir.set(dist.appResourcesRootDir.orNull?.asFile)
            }
        } catch (e: Throwable) {
            val extra = if (e is NoSuchMethodError && "dsl.JvmApplication" in e.message!!) {
                "If you're using Compose 1.1 or below, try upgrading to Compose 1.2 or higher, or using version 1.0.1 of the Conveyor Gradle plugin."
            } else {
                ""
            }
            throw Exception("Could not read Jetpack Compose configuration, likely plugin version incompatibility? $extra".trim(), e)
        }

        // Initialize appJar property
        val jarTask: Task = project.tasks.findByName("desktopJar") ?: project.tasks.findByName("jvmJar") ?: project.tasks.getByName("jar")
        appJar.set(jarTask.outputs.files.singleFile)

        // Initialize runtimeClasspath property
        val runtimeClasspathConfiguration =
            (project.configurations.findByName("runtimeClasspath") ?: project.configurations.getByName("desktopRuntimeClasspath")
            ?: project.configurations.getByName("jvmRuntimeClasspath"))
        // We need to resolve the runtimeClasspath before copying out the dependencies because the at the start of the resolution process
        // the set of dependencies can be changed via [Configuration.defaultDependencies] or [Configuration.withDependencies].
        // Also, we can't store a Configuration as a task input property because it doesn't serialize into the configuration cache.
        runtimeClasspath.addAll(project.files(runtimeClasspathConfiguration.resolve()).map { it.absolutePath })

        val currentMachineConfig = machineConfigs[Machine.current().toString()]!!

        // Exclude current machine specific config from the runtime classpath, to retain only the dependencies that should go
        // to all platforms.
        val currentMachineDependencies: DependencySet = currentMachineConfig.dependencies
        val commonClasspath = runtimeClasspathConfiguration.copyRecursive {
            // We need to filter the runtimeClasspath here, before making the recursive copy, otherwise the dependencies from the current
            // machine config won't match.
            it !in currentMachineDependencies && (!javafx || it.group != "org.openjfx")
        }

        // Make machine configs extend the common classpath so the dependencies are resolved correctly.
        val expandedConfigsMap: SortedMap<String, Configuration> = machineConfigs.mapNotNull { (machine, config) ->
            if (config.isEmpty) null else {
                machine to config.copy().extendsFrom(commonClasspath).copyRecursive()
            }
        }.toMap().toSortedMap()

        // If there are any expanded configs, use the intersection as the common files. Otherwise, just use all files from the common
        // classpath. If there are any expanded configs, we can't really use the common classpath, because it might need the platform
        // specific dependencies to properly resolve versions.
        val commonFilesAsFiles: Set<File> = if (expandedConfigsMap.isNotEmpty())
            expandedConfigsMap.values.map { it.files }.reduce { a, b -> a.intersect(b) }
        else
            commonClasspath.files
        commonFiles.addAll(commonFilesAsFiles.map { it.absolutePath })

        for ((platform: String, config: Configuration) in expandedConfigsMap) {
            expandedConfigs.put(platform, config.files.map { it.absolutePath }.toSortedSet())
        }
    }

    private val hoconForbiddenChars = setOf(
        '$', '"', '{', '}', '[', ']', ':', '=', ',', '+', '#', '`', '^', '?', '!', '@', '*', '&', '\\'
    )

    private fun hasHoconForbiddenChars(str: String) = str.any { it in hoconForbiddenChars }

    private fun quote(str: Any) = str.toString().let {
        if (hasHoconForbiddenChars(it))
            "\"" + it.replace("\\", "\\\\") + "\""
        else
            it
    }

    private fun StringBuilder.importFromComposePlugin() {
        appendLine()
        appendLine("// Config from the Jetpack Compose Desktop plugin.")
        composeMainClass.orNull?.let {
            appendLine("app.jvm.gui.main-class = " + quote(it))
            appendLine("app.linux.desktop-file.\"Desktop Entry\".StartupWMClass = " + quote(it.replace('.', '-')))
        }

        importJVMArgs(composeJvmArgs.get())

        composePackageName.orNull?.let { appendLine("app.fsname = " + quote(it)) }
        composeDescription.orNull?.let { appendLine("app.description = " + quote(it)) }
        composeVendor.orNull?.let { appendLine("app.vendor = " + quote(it)) }
        composeAppResourcesRootDir.orNull?.let {
            appendLine("app.jvm.system-properties.\"compose.application.resources.dir\" = ${quote("&&")}")
            for ((key, source) in mapOf(
                "inputs" to "common",
                "mac.inputs" to "macos",
                "windows.inputs" to "windows",
                "linux.inputs" to "linux",
                "mac.amd64.inputs" to "macos-x64",
                "mac.aarch64.inputs" to "macos-arm64",
                "windows.amd64.inputs" to "windows-x64",
                "windows.aarch64.inputs" to "windows-arm64",
                "linux.amd64.inputs" to "linux-x64",
                "linux.aarch64.inputs" to "linux-arm64",
            )) {
                val dir = it.resolve(source)
                if (dir.exists()) {
                    appendLine("app.$key += ${quote(dir)}")
                }
            }
        }

        // TODO(low): Import more stuff, including:
        //
        // - Notarization details?
        // - Icons?
    }

    private fun StringBuilder.importFromJavaFXPlugin() {
        try {
            if (javafx) {
                appendLine()
                appendLine("// Config from the OpenJFX plugin.")
                appendLine("include required(\"/stdlib/jvm/javafx/from-jmods.conf\")")
                appendLine("javafx.version = ${javafxVersion.get()}")
                appendLine(
                    "app.jvm.modules = ${'$'}{app.jvm.modules} " + javafxModules.get().joinToString(
                        ", ",
                        prefix = "[ ",
                        postfix = " ]"
                    )
                )
            }
        } catch (e: Throwable) {
            throw Exception("Could not read JavaFX configuration, possible version incompatibility?", e)
        }
    }

    private fun StringBuilder.importFromDependencyConfigurations() {
        appendLine()
        appendLine("// Inputs from dependency configurations and the JAR task.")
        appendLine("app.inputs += " + quote(appJar.get().toString()))

        if (commonFiles.get().isNotEmpty()) {
            appendLine("app.inputs = ${'$'}{app.inputs} [")
            for (entry in commonFiles.get().sorted()) {
                appendLine("    " + quote(entry.toString()))
            }
            appendLine("]")
        }

        // Emit platform specific artifacts into the right config sections.
        for ((platform, config: SortedSet<String>) in expandedConfigs.get()) {
            val files: Set<Any> = config - commonFiles.get().toSortedSet()
            if (files.isEmpty()) continue
            appendLine()
            appendLine("app.$platform.inputs = ${'$'}{app.$platform.inputs} [")
            for (entry in files) {
                appendLine("    " + quote(entry.toString()))
            }
            appendLine("]")
        }
    }

    private fun StringBuilder.importFromJavaPlugin() {
        if (mainClass.isPresent) {
            appendLine()
            appendLine("// Config from the application plugin.")
            val mainClassValue = quote(mainClass.get())
            appendLine("app.jvm.gui.main-class = $mainClassValue")
            appendLine("app.linux.desktop-file.\"Desktop Entry\".StartupWMClass = $mainClassValue")
            importJVMArgs(applicationDefaultJvmArgs.get())
            appendLine()
        }

        if (jvmLanguageVersion.isPresent) {
            val jvmVersion: JavaLanguageVersion? = jvmLanguageVersion.orNull
            val vendor: String = jvmVendorValue.getOrElse("ADOPTIUM")
            if (jvmVersion == null) {
                appendLine("// Java toolchain doesn't specify a version. Not importing a JDK.")
            } else {
                val conveyorVendor = if (vendor == "any") "openjdk" else when (vendor) {
                    "AMAZON" -> "amazon"
                    "AZUL" -> "azul"
                    "ORACLE" -> "openjdk"
                    "MICROSOFT" -> "microsoft"
                    "ADOPTIUM" -> "eclipse"
                    "GRAAL_VM" -> "graalvm"
                    else -> null
                }
                if (conveyorVendor != null) {
                    appendLine("// Config from the Java plugin.")
                    appendLine("include required(\"/stdlib/jdk/$jvmVersion/$conveyorVendor.conf\")")
                } else {
                    appendLine("// Gradle build requests a JVM from $vendor but this vendor isn't known to Conveyor at this time.")
                    appendLine("// You can still use it, you'll just have to add JDK inputs that define where to download or find it.")
                    appendLine("//")
                    appendLine("// Please see https://conveyor.hydraulic.dev/latest/configs/jvm/#importing-a-jvmjdk for assistance.")
                    appendLine("internal.conveyor.warnings += \"unknown-jdk-vendor:$vendor\"")
                }
            }
        }
    }

    private fun StringBuilder.importJVMArgs(jvmArgs: MutableIterable<String>) {
        val argsNotPointingIntoTree = jvmArgs
            .toList()
            .filterNot { rootProjectDir.get().toString() in it }
        if (argsNotPointingIntoTree.isNotEmpty()) {
            appendLine("app.jvm.options = ${'$'}{app.jvm.options} " +
                argsNotPointingIntoTree.joinToString(", ", "[ ", " ]") { quote(it) })
        }
    }

    protected fun generate(): String {
        return buildString {
            appendLine("// Generated by the Conveyor Gradle plugin.")
            appendLine()
            appendLine("// Gradle project data. The build directory is useful for importing built files.")
            appendLine("gradle.build-dir = ${quote(buildDirectory.get())}")
            appendLine("gradle.project-name = ${quote(projectName.get())}")
            appendLine("app.fsname = ${quote(projectName.get().lowercase())}")

            val version = projectVersion.get().toString()
            if (version.isBlank() || version == "unspecified")
                throw Exception("You must set the 'version' property of the project, because all package formats require one.")
            appendLine("app.version = $version")

            val group = projectGroup.get().toString()
            if (group.isBlank())
                throw Exception("You must set the 'group' property of the project, because some package formats require a reverse DNS name.")
            appendLine("app.rdns-name = $group.${'$'}{app.fsname}")

            // This strips deps so must run before we calculate dep configurations.
            importFromJavaFXPlugin()
            importFromJavaPlugin()
            importFromComposePlugin()
            importFromDependencyConfigurations()
        }
    }
}
