package io.github.nexalloy

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.support.ParameterDeclarations
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Supplies every APK under `app/binaries` to [FingerprintsKtTest].
 *
 * The directory is untracked and usually empty, so an empty result has to be a normal outcome:
 * the test class declares `allowZeroInvocations` and this provider returns an empty stream rather
 * than throwing. Previously a missing or empty folder failed the whole task, which is why the
 * fingerprint tests could never be wired into CI.
 */
class FilePathArgumentsProvider : ArgumentsProvider {
    override fun provideArguments(
        parameters: ParameterDeclarations,
        context: ExtensionContext
    ): Stream<out Arguments> {
        val testInputPath = Paths.get(".").resolve("binaries")

        if (!Files.isDirectory(testInputPath)) {
            println("No APK folder at ${testInputPath.toAbsolutePath()}; skipping fingerprint tests.")
            return Stream.empty()
        }

        val apks = Files.walk(testInputPath).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) &&
                        path.extension.equals("apk", ignoreCase = true) &&
                        path.normalize().none { it.name.startsWith(".") }
            }.toList()
        }

        if (apks.isEmpty()) {
            println("No APKs in ${testInputPath.toAbsolutePath()}; skipping fingerprint tests.")
            return Stream.empty()
        }

        println("Testing fingerprints against: ${apks.joinToString { it.name }}")
        return apks.stream().map { Arguments.of(it) }
    }
}
