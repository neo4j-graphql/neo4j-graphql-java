package org.neo4j.graphql

import graphql.language.TypeName
import org.neo4j.graphql.domain.directives.RelationshipDirective

object Constants {

    const val JS_COMPATIBILITY: Boolean = true
    const val ID_FIELD = "id"
    const val AND = "AND"
    const val NOT = "NOT"
    const val OR = "OR"
    const val LIMIT = "limit"
    const val OFFSET = "offset"
    const val SORT = "sort"
    const val FIRST = "first"
    const val AFTER = "after"
    const val DIRECTED = "directed"
    const val EDGE_FIELD = "edge"
    const val PROPERTIES_FIELD = "properties"
    const val TOTAL_COUNT = "totalCount"
    const val PAGE_INFO = "pageInfo"
    const val EDGES_FIELD = "edges"
    const val CURSOR_FIELD = "cursor"
    const val NODE_FIELD = "node"
    const val RELATIONSHIP_FIELD = "relationship"
    const val TYPENAME_IN = "typename_IN"

    const val RESOLVE_TYPE = "__resolveType"
    const val RESOLVE_ID = "__id"

    const val X = "x"
    const val Y = "y"
    const val Z = "z"
    const val LONGITUDE = "longitude"
    const val LATITUDE = "latitude"
    const val HEIGHT = "height"
    const val CRS = "crs"
    const val SRID = "srid"

    const val POINT_TYPE = "Point"
    const val CARTESIAN_POINT_TYPE = "CartesianPoint"
    const val POINT_INPUT_TYPE = "PointInput"
    const val CARTESIAN_POINT_INPUT_TYPE = "CartesianPointInput"

    const val BOOLEAN = "Boolean"
    const val ID = "ID"
    const val STRING = "String"
    const val INT = "Int"
    const val FLOAT = "Float"
    const val DATE = "Date"
    const val TIME = "Time"
    const val LOCAL_TIME = "LocalTime"
    const val DATE_TIME = "DateTime"
    const val LOCAL_DATE_TIME = "LocalDateTime"
    const val BIG_INT = "BigInt"
    const val DURATION = "Duration"

    val TEMPORAL_TYPES = setOf(DATE, TIME, LOCAL_TIME, DATE_TIME, LOCAL_DATE_TIME)
    val POINT_TYPES = setOf(POINT_TYPE, CARTESIAN_POINT_TYPE)

    val RESERVED_INTERFACE_FIELDS = mapOf(
        NODE_FIELD to "Interface field name 'node' reserved to support relay See https://relay.dev/graphql/",
        CURSOR_FIELD to "Interface field name 'cursor' reserved to support relay See https://relay.dev/graphql/"
    )

    val FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES = setOf(
        RelationshipDirective.NAME,
    )

    const val TYPE_NAME = "__typename"

    const val OPTIONS = "options"
    const val WHERE = "where"

    object Types {
        val ID = TypeName("ID")
        val Int = TypeName(INT)
        val Float = TypeName(FLOAT)
        val String = TypeName(STRING)
        val Boolean = TypeName("Boolean")

        val PageInfo = TypeName("PageInfo")
        val QueryOptions = TypeName("QueryOptions")
        val SortDirection = TypeName("SortDirection")
        val PointDistance = TypeName("PointDistance")
        val CartesianPointDistance = TypeName("CartesianPointDistance")
    }


    object SpatialReferenceIdentifier {
        const val wgs_84_2D = 4326
        const val wgs_84_3D = 4979
        const val cartesian_2D = 7203
        const val cartesian_3D = 9157

    }
}
