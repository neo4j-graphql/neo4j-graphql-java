package org.neo4j.graphql.augmented_schema_generator.plugin

import graphql.schema.idl.SchemaPrinter
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.shared.model.fileset.FileSet
import org.apache.maven.shared.model.fileset.util.FileSetManager
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * Simple Mojo generating an augmented schema and writes it to the specified outputDirectory
 */
@Mojo(name = "generate-schema", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
class AugmentedSchemaGeneratorMojo : AbstractMojo() {

    /**
     * The configuration to use to augment a schema
     */
    @Parameter
    private val schemaConfig: SchemaConfig = SchemaConfig()


    /**
     * The folder to which the generated augmented schemas are written
     */
    @Parameter(required = true)
    private lateinit var outputDirectory: File

    /**
     * A set of schemas to be augmented
     */
    @Parameter(required = true)
    private lateinit var fileset: FileSet

    @Inject
    private lateinit var fileSetManager: FileSetManager

    @ExperimentalPathApi
    @Throws(MojoExecutionException::class)
    override fun execute() {
        val includedFiles: Array<String> = fileSetManager.getIncludedFiles(fileset)
        val schemaPrinter = SchemaPrinter(SchemaPrinter.Options
            .defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeSchemaDefinition(true)
            .includeIntrospectionTypes(false)
        )

        includedFiles.forEach { file ->
            val schemaContent = java.nio.file.Path.of(fileset.directory, file).readText(Charset.defaultCharset())
            val schema = SchemaBuilder.buildSchema(schemaContent, schemaConfig)
            val augmentedSchema = schemaPrinter.print(schema)
            val outputfile = File(outputDirectory, file)
            outputfile.parentFile.mkdirs()
            outputfile.writeText(augmentedSchema)
            log.info("writing augmented schema to $outputfile")
        }
    }
}
