package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all logic related to custom Cypher queries declared by fields with a @cypher directive
 */
class CypherDirectiveHandler(
        private val type: GraphQLFieldsContainer?,
        private val isQuery: Boolean,
        private val cypherDirective: Cypher,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcher(fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            val cypherDirective = fieldDefinition.cypherDirective() ?: return null
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
            val isQuery = operationType == OperationType.QUERY
            return CypherDirectiveHandler(type, isQuery, cypherDirective, fieldDefinition)
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val query = if (isQuery) {
            val nestedQuery = cypherDirective(variable, fieldDefinition, field, cypherDirective)
            org.neo4j.cypherdsl.core.Cypher.unwind(nestedQuery).`as`(variable)
        } else {
            val args = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective)

            val value = org.neo4j.cypherdsl.core.Cypher.name("value")
            org.neo4j.cypherdsl.core.Cypher.call("apoc.cypher.doIt")
                .withArgs(*args)
                .yield(value)
                .with(org.neo4j.cypherdsl.core.Cypher.property(value, Functions.head(org.neo4j.cypherdsl.core.Cypher.call("keys").withArgs(value).asFunction())).`as`(variable))
        }
        val node = org.neo4j.cypherdsl.core.Cypher.anyNode(variable)
        val readingWithWhere = if (type != null) {
            val projectionEntries = projectFields(node, field, type, env)
            query.returning(node.project(projectionEntries).`as`(field.aliasOrName()))
        } else {
            query.returning(node.`as`(field.aliasOrName()))
        }
        val ordering = orderBy(node, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments)

        val resultWithSkipLimit = readingWithWhere
            .let { if (ordering != null) skipLimit.format(it.orderBy(*ordering.toTypedArray())) else skipLimit.format(it) }

        return resultWithSkipLimit.build()
    }
}
