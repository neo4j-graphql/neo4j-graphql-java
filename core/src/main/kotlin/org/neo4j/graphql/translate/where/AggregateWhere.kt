package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.fields.RelationField

class AggregateWhere(val schemaConfig: SchemaConfig,val  queryContext: QueryContext?) {

    fun createAggregateWhere(chainStr: String, field: RelationField, varName: PropertyContainer, aggregation: Any?): Condition? {
        return null
    }
}
