package org.neo4j.graphql

import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer

abstract class AugmentationHandler(val schemaConfig: SchemaConfig) {
    enum class OperationType {
        QUERY,
        MUTATION
    }

    open fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {}

    abstract fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>?

}
