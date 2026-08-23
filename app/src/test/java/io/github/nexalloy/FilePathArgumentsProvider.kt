package io.github.nexalloy

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.support.ParameterDeclarations
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream
import kotlin.io.path.Path
import kotlin.io.path.name

class FilePathArgumentsProvider : ArgumentsProvider {
    override fun provideArguments(
        parameters: ParameterDeclarations,
        context: ExtensionContext
    ): Stream<out Arguments> {
        println(Path(".").toAbsolutePath())
        val projectDir = Paths.get(".") //.toAbsolutePath().normalize()
        val testInputPath = projectDir.resolve("binaries")

        // Missing fixtures is a normal state, not a bug: the APKs are large and not
        // redistributable, so a clean checkout has none. Throwing here surfaced as
        // `initializationError` with the real cause buried, which reads like the test
        // harness is broken rather than "there is nothing to test against". The Gradle
        // test task skips execution entirely in that case (see app/build.gradle.kts);
        // this empty stream is the belt to that braces.
        if (!Files.exists(testInputPath)) {
            System.err.println(
                "no APK fixtures at ${testInputPath.toAbsolutePath().normalize()} -- " +
                    "nothing to fingerprint; drop target APKs there to run this suite."
            )
            return Stream.empty()
        }

        return Files.walk(testInputPath).filter { path ->
                Files.isRegularFile(path) && path.normalize().none { it.name.startsWith(".") }
            }.map { Arguments.of(it) }
    }
}