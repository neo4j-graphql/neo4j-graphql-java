package org.neo4j.graphql.handler

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import org.neo4j.graphql.SchemaConfig

/**
 * This is a base class for all Node or Relation related data fetcher which allows batch input.
 */
abstract class BaseDataFetcherForContainerBatch(schemaConfig: SchemaConfig, val typeName: String) : BaseDataFetcherForContainer(schemaConfig) {

    override fun getType(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType, graphQLSchema: GraphQLSchema): GraphQLFieldsContainer {
        return graphQLSchema.getType(typeName) as GraphQLFieldsContainer
    }
}
