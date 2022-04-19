package org.neo4j.graphql

object DirectiveConstants {

    const val ALIAS = "alias"
    const val AUTH = "auth"
    const val COALESCE = "coalesce"
    const val DEFAULT = "default"
    const val TIMESTAMP = "timestamp"
    const val UNIQUE = "unique"
    const val COMPUTED = "computed"
    const val NODE = "node"
    const val READ_ONLY = "readonly"
    const val WRITE_ONLY = "writeonly"
    const val EXCLUDE = "exclude"
    const val PRIVATE = "private" // TODO do we need this one?
    const val FULLTEXT = "fulltext"
    const val QUERY_OPTIONS = "queryOptions"

    const val ID = "id"
    const val IGNORE = "ignore"
    const val RELATIONSHIP = "relationship"
    const val RELATIONSHIP_PROPERTIES = "relationshipProperties"
    const val RELATION = "relation"
    const val RELATION_NAME = "name"
    const val RELATION_DIRECTION = "direction"
    const val RELATION_FROM = "from"
    const val RELATION_TO = "to"

    const val CYPHER = "cypher"
    const val CYPHER_STATEMENT = "statement"
    const val CYPHER_PASS_THROUGH = "passThrough"

    const val PROPERTY = "property"
    const val PROPERTY_NAME = "name"

    const val DYNAMIC = "dynamic"
    const val DYNAMIC_PREFIX = "prefix"

    val LIB_DIRECTIVES = setOf(
        ALIAS,
        AUTH,
        COALESCE,
        DEFAULT,
        TIMESTAMP,
        UNIQUE,
        NODE,
        READ_ONLY,
        WRITE_ONLY,
        EXCLUDE,
        PRIVATE,
        FULLTEXT,
        QUERY_OPTIONS,
        ID,
        IGNORE,
        RELATIONSHIP,
        RELATIONSHIP_PROPERTIES,
        CYPHER,
        PROPERTY,
    )

}
