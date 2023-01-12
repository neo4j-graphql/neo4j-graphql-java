package org.neo4j.graphql.schema.relations

/**
 * Base class to augment a relation field
 */
interface RelationFieldBaseAugmentation {

    fun generateFieldCreateIT(): String?

    fun generateFieldUpdateIT(): String?

    fun generateFieldConnectOrCreateIT(): String?

    fun generateFieldConnectIT(): String?

    fun generateFieldDisconnectIT(): String?

    fun generateFieldDeleteIT(): String?

    fun generateFieldRelationCreateIT(): String?

    fun generateFieldWhereIT(): String?

    fun generateFieldConnectionWhereIT(): String?
}
