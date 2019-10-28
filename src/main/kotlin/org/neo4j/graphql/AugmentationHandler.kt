package org.neo4j.graphql

import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType

abstract class AugmentationHandler(val schemaConfig: SchemaConfig) {
    companion object {
        const val QUERY = "Query"
        const val MUTATION = "Mutation"
    }

    open fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {}

    abstract fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>?

}