package org.neo4j.graphql.handler

/**
 * Interface used in conjunction with [org.neo4j.graphql.SchemaConfig.wrapMutationResults] to retrieve the nested result field
 */
interface HasNestedData {
    /**
     * The field name for the nested query result
     */
    fun getDataField(): String
}
