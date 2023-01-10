package org.neo4j.graphql

import graphql.language.TypeName

object Constants {

    const val DATA = "data"
    const val AND = "AND"
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
    const val ON = "_on"
    const val SCORE = "score"

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

    const val AGGREGATION_SELECTION_FIELD_SUFFIX = "AggregationSelection"
    const val AGGREGATION_SELECTION_NODE_SUFFIX = "NodeAggregateSelection"
    const val AGGREGATION_SELECTION_EDGE_SUFFIX = "EdgeAggregateSelection"

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

    const val OPTIONS = "options"
    const val FULLTEXT = "fulltext"
    val OPTIONS_TYPE = TypeName("QueryOptions")

    const val WHERE = "where"

    const val NEO4J_QUERY_CONTEXT = "NEO4J_QUERY_CONTEXT"

    val PREDICATE_JOINS = setOf(AND, OR)

    object FieldSuffix {
        const val Aggregate = "Aggregate"

    }
    object OutputTypeSuffix {
        const val Connection = "Connection"
        const val Edge = "Edge"
    }

    object InputTypeSuffix {

        const val CreateInput = "CreateInput"
        const val CreateFieldInput = "CreateFieldInput"

        const val ConnectInput = "ConnectInput"
        const val ConnectFieldInput = "ConnectFieldInput"

        const val ConnectOrCreateInput = "ConnectOrCreateInput"
        const val ConnectOrCreateFieldInput = "ConnectOrCreateFieldInput"
        const val ConnectOrCreateWhere = "ConnectOrCreateWhere"
        const val OnCreate = "OnCreate"
        const val OnCreateInput = "OnCreateInput"

        const val FieldInput = "FieldInput"
        const val RelationInput = "RelationInput"

        const val DeleteInput = "DeleteInput"
        const val DeleteFieldInput = "DeleteFieldInput"

        const val UpdateInput = "UpdateInput"
        const val UpdateFieldInput = "UpdateFieldInput"
        const val UpdateConnectionInput = "UpdateConnectionInput"

        const val DisconnectInput = "DisconnectInput"
        const val DisconnectFieldInput = "DisconnectFieldInput"


        const val ConnectWhere = "ConnectWhere"

        const val ConnectionWhere = "ConnectionWhere"

        const val Where = "Where"
        const val UniqueWhere = "UniqueWhere"
        const val ImplementationsWhere = "ImplementationsWhere"

        const val AggregateInput = "AggregateInput"
        const val NodeAggregationWhereInput = "NodeAggregationWhereInput"
        const val EdgeAggregationWhereInput = "EdgeAggregationWhereInput"

        const val FulltextSort = "FulltextSort"
        const val FulltextWhere = "FulltextWhere"

        const val Options = "Options"
        const val Sort = "Sort"
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
    }

    object ArrayOperations {
        const val PUSH = "PUSH"
        const val POP = "POP"
    }
    object Math {

        const val INCREMENT = "INCREMENT"
        const val DECREMENT = "DECREMENT"
        const val ADD = "ADD"
        const val SUBTRACT = "SUBTRACT"
        const val DIVIDE = "DIVIDE"
        const val MULTIPLY = "MULTIPLY"
    }

    const val AUTH_FORBIDDEN_ERROR = "@neo4j/graphql/FORBIDDEN"
    const val AUTH_UNAUTHENTICATED_ERROR = "@neo4j/graphql/UNAUTHENTICATED"
    const val RELATIONSHIP_REQUIREMENT_PREFIX = "@neo4j/graphql/RELATIONSHIP-REQUIRED";

    // TODO migrate to old approach
    val WHERE_REG_EX =
        Regex("(?<fieldName>[_A-Za-z]\\w*?)(?<isAggregate>Aggregate)?(?:_(?<operator>NOT|NOT_IN|IN|NOT_INCLUDES|INCLUDES|MATCHES|NOT_CONTAINS|CONTAINS|NOT_STARTS_WITH|STARTS_WITH|NOT_ENDS_WITH|ENDS_WITH|LT|LTE|GT|GTE|DISTANCE|ALL|NONE|SINGLE|SOME))?\$")

}
