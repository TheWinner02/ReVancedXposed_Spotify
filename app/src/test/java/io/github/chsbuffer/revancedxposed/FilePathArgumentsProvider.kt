package io.github.chsbuffer.revancedxposed

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

        if (!Files.exists(testInputPath)) {
            throw IllegalStateException("APKs folder not found: $testInputPath")
        }

        return Files.walk(testInputPath).filter { path ->
                Files.isRegularFile(path) && path.normalize().none { it.name.startsWith(".") }
            }.map { Arguments.of(it) }
    }
}