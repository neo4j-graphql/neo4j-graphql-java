package org.neo4j.graphql.handler

/**
 * Interface used in conjunction with [org.neo4j.graphql.SchemaConfig.enableStatistics] to mark the relevant result fields
 */
interface HasNestedStatistics : HasNestedData {

    /**
     * the field name for the statistic data
     */
    fun getStatisticsField(): String
}
