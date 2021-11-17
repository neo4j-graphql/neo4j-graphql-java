package org.neo4j.graphql.handler

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLType
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.getRelevantFieldDefinition
import org.neo4j.graphql.inner
import org.neo4j.graphql.name

/**
 * This is a base class for all Node or Relation related data fetcher which allows batch input.
 */
abstract class BaseDataFetcherForContainerBatch(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {
    companion object {
        const val INPUT = "input"
    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        type = getType(fieldDefinition, parentType)
        val input = fieldDefinition.getArgument(INPUT)
                ?: throw IllegalStateException("${parentType.name()}.${fieldDefinition.name} expected to have an argument named $INPUT")

        val inputType = input.type.inner() as GraphQLInputObjectType
        inputType.fieldDefinitions
            .onEach { arg ->
                if (arg.defaultValue != null) {
                    defaultFields[arg.name] = arg.defaultValue
                }
            }
            .mapNotNull { type.getRelevantFieldDefinition(it.name) }
            .forEach { field -> addPropertyField(field) }
    }
}
