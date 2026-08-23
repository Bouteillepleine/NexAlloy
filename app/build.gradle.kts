import com.google.protobuf.gradle.proto
import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.NodeChild
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
}

val gitCommitHashProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

val gitCommitDateProvider = providers.exec {
    commandLine("git log -1 --format=%ct".split(" "))
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

// Which repository the in-app updater polls. Hardcoded to the upstream org before, so
// a fork built from here offered upstream's APK -- same applicationId, different signing
// key, so Android refuses the install and the dialog leads nowhere. Resolved at the
// script level rather than inside defaultConfig, where `findProperty` resolves through
// an enclosing scope rather than the block's own receiver.
val updateOwner = (project.findProperty("updateOwner") as String?)?.takeIf { it.isNotBlank() }
    ?: "Bouteillepleine"
val updateRepo = (project.findProperty("updateRepo") as String?)?.takeIf { it.isNotBlank() }
    ?: "NexAlloy"

android {
    namespace = "io.github.nexalloy"

    defaultConfig {
        applicationId = "io.github.chsbuffer.revancedxposed"
        versionCode = 106
        versionName = "2.0.$versionCode"
        val patchVersion = Properties().apply {
            rootProject.file("morphe-patches/gradle.properties").inputStream().use { load(it) }
        }["version"]
        buildConfigField("String", "PATCH_VERSION", "\"$patchVersion\"")
        buildConfigField("String", "COMMIT_HASH", "\"${gitCommitHashProvider.get().trim()}\"")
        buildConfigField("long", "COMMIT_DATE", "${gitCommitDateProvider.get().trim()}L")

        // Where the in-app updater looks for releases; see updateOwner/updateRepo above.
        buildConfigField("String", "UPDATE_OWNER", "\"$updateOwner\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x4b")
    }
    packaging.resources {
        excludes.addAll(
            arrayOf(
                "META-INF/**", "**.bin"
            )
        )
    }
    // Release signing is OPTIONAL, and deliberately so.
    //
    // CI used to write signing.properties unconditionally, so on a fork with no
    // KEYSTORE secret the file existed, every value in it was the empty string and
    // key.jks was zero bytes -- which produced a release signing config that could
    // only fail, several minutes into the build. Presence of the file is therefore
    // not enough: the values have to be populated and the keystore has to be a real
    // file. When it isn't, fall back to the debug key so the artifact is still
    // installable for testing rather than an unsigned APK nobody can use.
    val ksFile = rootProject.file("signing.properties")
    val ksProps = Properties().apply {
        if (ksFile.exists()) ksFile.inputStream().use { load(it) }
    }
    fun prop(name: String) = (ksProps[name] as String?)?.trim().orEmpty()
    val ksStore = prop("KEYSTORE_FILE").takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    val hasReleaseKey = ksStore != null &&
        ksStore.isFile &&
        ksStore.length() > 0 &&
        prop("KEYSTORE_PASSWORD").isNotEmpty() &&
        prop("KEYSTORE_ALIAS").isNotEmpty() &&
        prop("KEYSTORE_ALIAS_PASSWORD").isNotEmpty()

    // Stable identity for unsigned-by-secret builds.
    //
    // Falling back to the DEBUG key looked fine until you tried to update: CI runners
    // generate a fresh ~/.android/debug.keystore per job, so consecutive nightlies were
    // signed by different identities (93c73027… then 9d5e2818…) and Android refused
    // every in-place update. This keystore is committed on purpose and its password is
    // public — it is not a secret and grants nothing except the ability to build an APK
    // that can update a NIGHTLY install. Tagged releases use the real key from secrets;
    // if you care about the nightly channel being un-impersonable, set up the release
    // key and stop publishing nightlies.
    val nightlyStore = rootProject.file("nightly.keystore")
    val hasNightlyKey = nightlyStore.isFile && nightlyStore.length() > 0

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storePassword = prop("KEYSTORE_PASSWORD")
                keyAlias = prop("KEYSTORE_ALIAS")
                keyPassword = prop("KEYSTORE_ALIAS_PASSWORD")
                storeFile = ksStore
            }
        }
        if (!hasReleaseKey && hasNightlyKey) {
            create("nightly") {
                storeFile = nightlyStore
                storePassword = "nexalloy-nightly"
                keyAlias = "nightly"
                keyPassword = "nexalloy-nightly"
            }
        }
    }
    buildFeatures.buildConfig = true
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = when {
                hasReleaseKey -> signingConfigs.getByName("release")
                hasNightlyKey -> {
                    logger.lifecycle(
                        "NexAlloy: no release keystore; signing with the committed nightly " +
                            "key. Installs and updates other nightlies, but cannot update a " +
                            "release-signed install."
                    )
                    signingConfigs.getByName("nightly")
                }
                else -> {
                    logger.lifecycle(
                        "NexAlloy: no release or nightly keystore; falling back to the debug " +
                            "key. NOTE: CI generates a new debug key per run, so consecutive " +
                            "builds will not update each other."
                    )
                    signingConfigs.getByName("debug")
                }
            }
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        named("main") {
            val srcDirs = arrayOf(
                "../morphe-patches/extensions/shared/library/src/main/java",
                "../morphe-patches/extensions/shared-youtube/library/src/main/java",
                "../morphe-patches/extensions/youtube/src/main/java",
                "../morphe-patches/extensions/music/src/main/java",
                "../morphe-patches/extensions/reddit/src/main/java",
                "../morphe-patches-library/extension-library/src/main/java"
            )
            java.directories += srcDirs
            kotlin.directories += srcDirs

            proto {
                srcDirs(
                    "../morphe-patches/extensions/youtube/src/main/proto",
                    "../morphe-patches/extensions/shared-youtube/library/src/main/proto",
                )
            }
        }
    }
}

// Exclude Morphe-specific files that depend on protobuf/innertube/javascriptengine
// which are not available in the Xposed module build context.
tasks.withType<JavaCompile>().configureEach {
    exclude(
        "**/patches/HideRelatedVideosPatch.java",
        "**/patches/playback/quality/PrioritizeVideoQualityPatch.java",
        "**/OAuth2Preference.java",
        "**/SpoofVideoStreamsSignInPreference.java",
        "**/SpoofSignaturePatch.java",
    )
}
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
            "-Xno-call-assertions",
            "-Xcontext-parameters"
        )
        jvmTarget = JvmTarget.JVM_17
    }
}
// The fingerprint suite runs against REAL APKs dropped into <root>/binaries -- it
// resolves every fingerprint against the actual app and is the thing that catches a
// YouTube update breaking a patch. Those APKs are large and not redistributable, so
// they are not in the repository, and with the folder absent JUnit fails the whole
// parameterized class with `initializationError` for producing zero invocations.
//
// So: always COMPILE the tests (that alone catches harness drift against DexKit and
// the patch APIs, and costs nothing), and EXECUTE them whenever fixtures are present.
// CI is therefore green on a clean checkout and becomes a real gate the moment APKs
// are supplied -- by a developer locally, or by a cache/artifact step here.
// Decided at CONFIGURATION time and applied as `enabled`, NOT via `onlyIf {}`. An
// onlyIf lambda written in a .gradle.kts is itself a reference to the script object,
// which the configuration cache cannot serialize -- it rejects the build with
// "cannot serialize Gradle script object references" no matter how little the lambda
// captures. Setting a plain Boolean property has no such problem.
val testFixtureDir: File = rootProject.file("binaries")
val testFixtureCount: Int = testFixtureDir.takeIf { it.isDirectory }
    ?.walkTopDown()
    ?.count { it.isFile && !it.name.startsWith(".") }
    ?: 0
val hasTestFixtures = testFixtureCount > 0

if (hasTestFixtures) {
    logger.lifecycle("NexAlloy: fingerprint suite will run against $testFixtureCount APK(s)")
} else {
    logger.lifecycle(
        "NexAlloy: no APKs in ${testFixtureDir.path} -- the fingerprint suite will be " +
            "compiled but not executed. Drop target APKs there to run it."
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
    enabled = hasTestFixtures
}

dependencies {
//    implementation(libs.dexkit)

    // DexKit fork with instruction operand introspection
    // https://github.com/NexAlloy/DexKit/commit/046c0484b37e6a2100dd7bcc16748132c45dd2d9
    implementation(":dexkit-android@aar")
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26") // dexkit dependency
    implementation(libs.annotation)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.fuel)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.jadx.core)
    testImplementation(libs.slf4j.simple)
    debugImplementation(kotlin("reflect"))
    compileOnly(libs.xposed)
//    implementation(project(":extensions"))
    compileOnly(project(":stub"))
    implementation(libs.androidx.javascriptengine)
    implementation(libs.protobuf.javalite)
    // Settings UI only. These are loaded in the MODULE's own process, never in a
    // hooked app -- MainHook and PatchExecutor touch none of them.
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.preference)
    implementation(libs.collections4)
    implementation(libs.lang3)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

abstract class GenerateStringsTask @Inject constructor(
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun writeNode(builder: MarkupBuilder, node: Any?) {
        if (node !is NodeChild) return
        val attributes = node.attributes()
        builder.withGroovyBuilder {
            if (node.children().any()) {
                node.name()(attributes) {
                    node.children().forEach {
                        writeNode(builder, it)
                    }
                }
            } else {
                node.name()(attributes, node.text())
            }
        }
    }

    /**
     * Morphe addresources structure:
     *   values/youtube/strings.xml, values/shared/strings.xml, etc.
     * Each XML is flat: <resources> <string name="...">...</string> ... </resources>
     *
     * Merge all subdirectory XMLs into a single output file per variant.
     */
    private fun mergeResources(inputFiles: List<File>, output: File) {
        output.parentFile.mkdirs()
        output.writer().use { writer ->
            val builder = MarkupBuilder(writer)
            builder.doubleQuotes = true
            builder.withGroovyBuilder {
                val keys = mutableSetOf<String>()
                "resources" {
                    for (inputFile in inputFiles) {
                        if (!inputFile.exists()) continue
                        val inputXml = XmlSlurper().parse(inputFile)
                        // Flat structure: direct children of <resources>
                        inputXml.children().forEach {
                            if (it !is NodeChild) return@forEach
                            val key = it.attributes()["name"] as? String ?: return@forEach
                            if (keys.contains(key)) return@forEach
                            writeNode(builder, it)
                            keys.add(key)
                        }
                    }
                }
            }
        }
    }

    // Subdirectories within each variant that contain resource files.
    private val subDirs = listOf("shared", "shared-youtube", "youtube", "music", "reddit", "sponsorblock")

    @TaskAction
    fun action() {
        val inputDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile

        runCatching {
            // Process each variant directory (values, values-xx-rYY, ...)
            inputDir.listFiles()?.filter { it.isDirectory }?.forEach { variant ->
                val genResDir = File(outputDir, variant.name).apply { mkdirs() }

                // Merge strings.xml from all subdirectories
                val stringFiles = subDirs.map { File(variant, "$it/strings.xml") }
                mergeResources(stringFiles, File(genResDir, "strings.xml"))

                // Merge arrays.xml from all subdirectories
                val arrayFiles = subDirs.map { File(variant, "$it/arrays.xml") }
                if (arrayFiles.any { it.exists() }) {
                    mergeResources(arrayFiles, File(genResDir, "arrays.xml"))
                }
            }
        }.onFailure {
            System.err.println(it)
            throw it
        }
    }
}

abstract class CopyResourcesTask @Inject constructor() : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun action() {
        val baseDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()

        val resourcePaths = mapOf(
            "qualitybutton/drawable" to null,
            "settings/drawable" to null,
            "settings/menu" to null,
            "settings/layout" to listOf("morphe_settings_with_toolbar.xml"),
            "sponsorblock/drawable" to null,
            "sponsorblock/layout" to listOf("morphe_sb_skip_sponsor_button.xml"),
            "swipecontrols/drawable" to null,
            "copyvideolinkbutton/drawable" to null,
            "downloads/drawable" to null,
            "speedbutton/drawable" to null,
            "navigationbuttons/drawable" to null,
        )

        for ((resourcePath, excludes) in resourcePaths) {
            val dir = resourcePath.substringAfter('/')
            val sourceDir = File(baseDir, resourcePath)
            val targetDir = File(outputDir, dir)
            sourceDir.listFiles()?.forEach { file ->
                if (excludes == null || !excludes.contains(file.name)) {
                    file.copyTo(File(targetDir, file.name), overwrite = true)
                }
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.resources.excludes.add("kotlin/**")
    }

    onVariants { variant ->
        val variantName = variant.name.uppercaseFirstChar()
        val strTask = project.tasks.register<GenerateStringsTask>("generateStrings$variantName") {
            inputDirectory.set(project.file("../morphe-patches/patches/src/main/resources/addresources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            strTask, GenerateStringsTask::outputDirectory
        )

        val resTask = project.tasks.register<CopyResourcesTask>("copyResources$variantName") {
            inputDirectory.set(project.file("../morphe-patches/patches/src/main/resources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            resTask, CopyResourcesTask::outputDirectory
        )
    }
}
