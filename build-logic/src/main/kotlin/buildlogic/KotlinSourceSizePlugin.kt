package buildlogic

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyKotlinFileSizeTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val current = sourceFiles.files.asSequence()
            .filter { file -> file.isFile && file.extension == "kt" }
            .map { file ->
                val path = KotlinSourceSizePolicy.normalizePath(
                    root.relativize(file.toPath().toAbsolutePath().normalize()).toString(),
                )
                path to KotlinSourceSizePolicy.countPhysicalLines(file.readText(Charsets.UTF_8))
            }
            .filterNot { (path, _) -> KotlinSourceSizePolicy.isExcluded(path) }
            .toMap()
        val baseline = try {
            KotlinSourceSizePolicy.parseBaseline(
                baselineFile.get().asFile.readText(Charsets.UTF_8),
            )
        } catch (error: RuntimeException) {
            throw GradleException(error.message ?: "Invalid Kotlin source size baseline", error)
        }
        val violations = KotlinSourceSizePolicy.evaluate(
            currentLines = current,
            baselineLines = baseline,
            allowedBaselineCaps = KOTLIN_SOURCE_BASELINE_CAPS,
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Kotlin source file size verification failed:")
                    violations.forEach { violation -> appendLine(" - ${violation.report()}") }
                    append("Handwritten Kotlin files must stay at or below $KOTLIN_SOURCE_MAX_LINES physical lines.")
                },
            )
        }

        val largest = current.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        )
        logger.lifecycle(
            "Kotlin source size verified: {} files, maximum {} lines at {}, {} temporary baseline entries",
            current.size,
            largest?.value ?: 0,
            largest?.key ?: "<none>",
            baseline.size,
        )
    }
}

abstract class VerifyWebUiFileSizeTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val current = sourceFiles.files.filter { it.isFile }.associate { file ->
            KotlinSourceSizePolicy.normalizePath(
                root.relativize(file.toPath().toAbsolutePath().normalize()).toString(),
            ) to KotlinSourceSizePolicy.countPhysicalLines(file.readText(Charsets.UTF_8))
        }
        val violations = KotlinSourceSizePolicy.evaluate(current, emptyMap())
        if (violations.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("WebUI source file size verification failed:")
                violations.forEach { appendLine(" - ${it.report()}") }
                append("Handwritten WebUI JS and CSS must stay at or below $KOTLIN_SOURCE_MAX_LINES physical lines.")
            })
        }
        val largest = current.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        )
        logger.lifecycle(
            "WebUI source size verified: {} files, maximum {} lines at {}, no exceptions",
            current.size, largest?.value ?: 0, largest?.key ?: "<none>",
        )
    }
}

class KotlinSourceSizePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "buildlogic.kotlin-source-size must be applied to the root project"
        }
        val verify = project.tasks.register(
            "verifyKotlinFileSize",
            VerifyKotlinFileSizeTask::class.java,
            object : Action<VerifyKotlinFileSizeTask> {
                override fun execute(task: VerifyKotlinFileSizeTask) {
                    task.group = "verification"
                    task.description = "Verifies the physical-line budget for handwritten Kotlin sources."
                    task.repositoryRoot.set(project.layout.projectDirectory)
                    task.baselineFile.set(
                        project.layout.projectDirectory.file("config/kotlin-source-size-baseline.txt"),
                    )
                    task.sourceFiles.from(
                        project.fileTree(project.rootDir).apply {
                            include("**/*.kt")
                            exclude(
                                "**/.build-proot/**",
                                "**/.git/**",
                                "**/.gradle/**",
                                "**/.harness/**",
                                "**/.idea/**",
                                "**/.kotlin/**",
                                "**/.claude/**",
                                "**/.gemini/**",
                                "**/build/**",
                                "**/cache/**",
                                "**/caches/**",
                                "**/generated/**",
                                "site/**",
                                "thirdparty/**",
                            )
                        },
                    )
                }
            },
        )

        val verifyWebUi = project.tasks.register(
            "verifyWebUiFileSize",
            VerifyWebUiFileSizeTask::class.java,
            object : Action<VerifyWebUiFileSizeTask> {
                override fun execute(task: VerifyWebUiFileSizeTask) {
                    task.group = "verification"
                    task.description = "Verifies the physical-line budget for handwritten WebUI JS and CSS."
                    task.repositoryRoot.set(project.layout.projectDirectory)
                    task.sourceFiles.from(project.fileTree("app/src/main/assets/webui").apply {
                        include("**/*.js", "**/*.mjs", "**/*.css")
                        exclude("vendor/**")
                    })
                }
            },
        )
        verify.configure(object : Action<VerifyKotlinFileSizeTask> {
            override fun execute(task: VerifyKotlinFileSizeTask) {
                task.dependsOn(verifyWebUi)
            }
        })

        project.allprojects.forEach { candidate ->
            candidate.tasks.matching { task ->
                task.name == "check" || task.name == "test" || task.name == "preBuild"
            }.configureEach(
                object : Action<Task> {
                    override fun execute(task: Task) {
                        task.dependsOn(verify)
                    }
                },
            )
        }
    }
}
