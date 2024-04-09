package org.neo4j.graphql

import graphql.language.TypeName
import org.neo4j.graphql.domain.directives.CypherDirective
import org.neo4j.graphql.domain.directives.RelationshipDirective

object Constants {

    const val JS_COMPATIBILITY: Boolean = true
    const val JWT = "jwt"
    const val JWT_PREFIX = "\$jwt."
    const val ID_FIELD = "id"
    const val RELATIONSHIP_FIELD_NAME = "relationshipFieldName"
    const val CREATED_RELATIONSHIP = "createdRelationship"
    const val DELETED_RELATIONSHIP = "deletedRelationship"
    const val PREVIOUS_STATE = "previousState"
    const val DATA = "data"
    const val AND = "AND"
    const val NOT = "NOT"
    const val OR = "OR"
    const val FULLTEXT_PHRASE = "phrase"
    const val FULLTEXT_SCORE_EQUAL = "score_EQUAL"
    const val EMPTY_INPUT = "_emptyInput"
    const val LIMIT = "limit"
    const val OFFSET = "offset"
    const val SORT = "sort"
    const val FIRST = "first"
    const val AFTER = "after"
    const val DIRECTED = "directed"
    const val ON_CREATE_FIELD = "onCreate"
    const val INFO_FIELD = "info"
    const val INPUT_FIELD = "input"
    const val CREATE_FIELD = "create"
    const val CONNECT_FIELD = "connect"
    const val OVERWRITE_FIELD = "overwrite"
    const val CONNECT_OR_CREATE_FIELD = "connectOrCreate"
    const val UPDATE_FIELD = "update"
    const val DELETE_FIELD = "delete"
    const val DISCONNECT_FIELD = "disconnect"
    const val EDGE_FIELD = "edge"
    const val PROPERTIES_FIELD = "properties"
    const val COUNT = "count"
    const val TOTAL_COUNT = "totalCount"
    const val PAGE_INFO = "pageInfo"
    const val EDGES_FIELD = "edges"
    const val CURSOR_FIELD = "cursor"
    const val NODE_FIELD = "node"
    const val TYPENAME_IN = "typename_IN"

    @Deprecated("Do not use any longer")
    const val ON = "_on"
    const val SCORE = "score"
    const val RESOLVE_TYPE = "__resolveType"

    const val EVENT = "event"
    const val TIMESTAMP = "timestamp"


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

    const val MIN = "min"
    const val MAX = "max"
    const val AVERAGE = "average"
    const val SUM = "sum"
    const val SHORTEST = "shortest"
    const val LONGEST = "longest"

    val TEMPORAL_TYPES = setOf(DATE, TIME, LOCAL_TIME, DATE_TIME, LOCAL_DATE_TIME)
    val ZONED_TIME_TYPES = setOf(TIME, DATE_TIME)
    val POINT_TYPES = setOf(POINT_TYPE, CARTESIAN_POINT_TYPE)

    val RESERVED_INTERFACE_FIELDS = mapOf(
        NODE_FIELD to "Interface field name 'node' reserved to support relay See https://relay.dev/graphql/",
        CURSOR_FIELD to "Interface field name 'cursor' reserved to support relay See https://relay.dev/graphql/"
    )

    val FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES = setOf(
        DirectiveConstants.AUTH,
        RelationshipDirective.NAME,
        CypherDirective.NAME
    )

    const val TYPE_NAME = "__typename"


    const val OPTIONS = "options"
    const val FULLTEXT = "fulltext"
    const val WHERE = "where"

    val PREDICATE_JOINS = setOf(AND, OR)

    object OutputTypeSuffix {
        const val Connection = "Connection"
        const val Edge = "Edge"
    }

    object Types {
        val ID = TypeName("ID")
        val Int = TypeName(INT)
        val Float = TypeName(FLOAT)
        val String = TypeName(STRING)
        val Boolean = TypeName("Boolean")

        val DeleteInfo = TypeName("DeleteInfo")
        val PageInfo = TypeName("PageInfo")
        val CreateInfo = TypeName("CreateInfo")
        val UpdateInfo = TypeName("UpdateInfo")
        val QueryOptions = TypeName("QueryOptions")
        val SortDirection = TypeName("SortDirection")
        val PointDistance = TypeName("PointDistance")
        val CartesianPointDistance = TypeName("CartesianPointDistance")
        val FloatWhere = TypeName("FloatWhere")
        val EventType = TypeName("EventType")
        val Node = TypeName("Node")
        val JWTStandard = TypeName("JWTStandard")
    }


    object SpatialReferenceIdentifier {
        const val wgs_84_2D = 4326
        const val wgs_84_3D = 4979
        const val cartesian_2D = 7203
        const val cartesian_3D = 9157

    }

    const val AUTH_FORBIDDEN_ERROR = "@neo4j/graphql/FORBIDDEN"
    const val AUTH_UNAUTHENTICATED_ERROR = "@neo4j/graphql/UNAUTHENTICATED"
    const val RELATIONSHIP_REQUIREMENT_PREFIX = "@neo4j/graphql/RELATIONSHIP-REQUIRED"
    const val AUTHORIZATION_UNAUTHENTICATED = "Unauthenticated";

    // TODO migrate to old approach
    val WHERE_REG_EX =
        Regex("(?<fieldName>[_A-Za-z]\\w*?)(?<isAggregate>Aggregate)?(?:_(?<operator>NOT|NOT_IN|IN|NOT_INCLUDES|INCLUDES|MATCHES|NOT_CONTAINS|CONTAINS|NOT_STARTS_WITH|STARTS_WITH|NOT_ENDS_WITH|ENDS_WITH|LT|LTE|GT|GTE|DISTANCE|ALL|NONE|SINGLE|SOME))?\$")

}
