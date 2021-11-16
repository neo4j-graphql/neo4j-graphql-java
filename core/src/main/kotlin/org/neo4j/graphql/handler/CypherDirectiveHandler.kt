package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all logic related to custom Cypher queries declared by fields with a @cypher directive
 */
class CypherDirectiveHandler(schemaConfig: SchemaConfig) : BaseDataFetcher(schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            fieldDefinition.cypherDirective() ?: return null
            return CypherDirectiveHandler(schemaConfig)
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val fieldDefinition = env.fieldDefinition
        val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
        val cypherDirective = fieldDefinition.cypherDirective()
                ?: throw IllegalStateException("Expect field ${env.logField()} to have @cypher directive present")

        val node = org.neo4j.cypherdsl.core.Cypher.anyNode(variable)
        val ctxVariable = node.requiredSymbolicName
        val nestedQuery = cypherDirective(ctxVariable, fieldDefinition, env.arguments, cypherDirective, null)

        return org.neo4j.cypherdsl.core.Cypher.call(nestedQuery)
            .let { reading ->
                if (type == null || cypherDirective.passThrough) {
                    reading.returning(ctxVariable.`as`(field.aliasOrName()))
                } else {
                    val (fieldProjection, nestedSubQueries) = projectFields(node, type, env)
                    reading
                        .withSubQueries(nestedSubQueries)
                        .returning(ctxVariable.project(fieldProjection).`as`(field.aliasOrName()))
                }
            }
            .build()
    }
}
