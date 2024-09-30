package org.neo4j.graphql.schema.relations

/**
 * Base class to augment a relation field
 */
interface RelationFieldBaseAugmentation {

    fun generateFieldWhereIT(): String?

    fun generateFieldConnectionWhereIT(): String?
}
