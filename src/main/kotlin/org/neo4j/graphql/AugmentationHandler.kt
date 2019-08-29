package org.neo4j.graphql

import graphql.schema.*

abstract class AugmentationHandler(val schemaConfig: SchemaConfig) {
    companion object {
        const val QUERY = "Query"
        const val MUTATION = "Mutation"
    }

    abstract fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv)

    abstract fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>?

    fun input(name: String, type: GraphQLType): GraphQLArgument {
        return GraphQLArgument
            .newArgument()
            .name(name)
            .type((type.ref() as? GraphQLInputType)
                    ?: throw IllegalArgumentException("${type.innerName()} is not allowed for input")).build()
    }
}