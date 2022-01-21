package org.neo4j.graphql

import graphql.language.TypeName

object Constants {

    const val EMPTY_INPUT = "_emptyInput"
    const val LIMIT = "limit"
    const val OFFSET = "offset"
    const val SORT = "sort"
    const val FIRST = "first"
    const val AFTER = "after"
    const val ON_CREATE_FIELD = "onCreate"
    const val INFO_FIELD = "info"
    const val INPUT_FIELD = "input"
    const val CREATE_FIELD = "create"
    const val CONNECT_FIELD = "connect"
    const val CONNECT_OR_CREATE_FIELD = "connectOrCreate"
    const val UPDATE_FIELD = "update"
    const val DELETE_FIELD = "delete"
    const val DISCONNECT_FIELD = "disconnect"
    const val EDGE_FIELD = "edge"
    const val COUNT = "count"
    const val TOTAL_COUNT = "totalCount"
    const val PAGE_INFO = "pageInfo"
    const val EDGES_FIELD = "edges"
    const val CURSOR_FIELD = "cursor"
    const val NODE_FIELD = "node"

    const val POINT_TYPE = "Point"
    const val CARTESIAN_POINT_TYPE = "CartesianPoint"
    const val POINT_INPUT_TYPE = "PointInput"
    const val CARTESIAN_POINT_INPUT_TYPE = "CartesianPointInput"

    const val DATE = "Date"
    const val TIME = "Time"
    const val LOCAL_TIME = "LocalTime"
    const val DATE_TIME = "DateTime"
    const val LOCAL_DATE_TIME = "LocalDateTime"
    const val BIG_INT = "BigInt"
    const val DURATION = "Duration"

    val TEMPORAL_TYPES = setOf(DATE, TIME, LOCAL_TIME, DATE_TIME, LOCAL_DATE_TIME)
    val ZONED_TIME_TYPES = setOf(TIME, DATE_TIME)
    val POINT_TYPES = setOf(POINT_TYPE, CARTESIAN_POINT_TYPE)

    val RESERVED_INTERFACE_FIELDS = mapOf(
        NODE_FIELD to "Interface field name 'node' reserved to support relay See https://relay.dev/graphql/",
        CURSOR_FIELD to "Interface field name 'cursor' reserved to support relay See https://relay.dev/graphql/"
    )

    val FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES = setOf(
        DirectiveConstants.AUTH,
        DirectiveConstants.RELATIONSHIP,
        DirectiveConstants.CYPHER
    )
    val WHERE_AGGREGATION_OPERATORS = listOf("EQUAL", "GT", "GTE", "LT", "LTE")
    val WHERE_AGGREGATION_AVERAGE_TYPES = setOf("String", "Int", "Float", BIG_INT, DURATION)
    val WHERE_AGGREGATION_TYPES =
        setOf("ID", "String", "Float", "Int", BIG_INT, DATE_TIME, LOCAL_DATE_TIME, LOCAL_TIME, TIME, DURATION)

    const val OPTIONS = "options"
    const val FULLTEXT = "fulltext"
    val OPTIONS_TYPE = TypeName("QueryOptions")

    const val WHERE = "where"

    object Types {
        val ID = TypeName("ID")
        val Int = TypeName("Int")
        val Float = TypeName("Float")
        val String = TypeName("String")
        val Boolean = TypeName("Boolean")

        val DeleteInfo = TypeName("DeleteInfo")
        val PageInfo = TypeName("PageInfo")
        val CreateInfo = TypeName("CreateInfo")
        val UpdateInfo = TypeName("UpdateInfo")
        val QueryOptions = TypeName("QueryOptions")
        val SortDirection = TypeName("SortDirection")
        val PointDistance = TypeName("PointDistance")
        val CartesianPointDistance = TypeName("CartesianPointDistance")
    }

}
