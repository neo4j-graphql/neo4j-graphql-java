package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.naming.RelationshipNames

/**
 * Representation of the `@relationship` directive and its meta.
 */
class RelationField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    override val properties: RelationshipProperties?,
) : RelationBaseField(
    fieldName,
    type,
    annotations,
) {

    override val namings = RelationshipNames(this)

    init {
        properties?.addUsedByRelation(this)
    }

    val relationship get() = requireNotNull(annotations.relationship)

    /**
     * The type of the neo4j relation
     */
    val relationType get() = relationship.type
    val direction get() = relationship.direction
    val queryDirection get() = relationship.queryDirection

    enum class Direction {
        IN, OUT
    }

    enum class QueryDirection {
        DIRECTED,
        UNDIRECTED,
    }

    fun createQueryDslRelation(
        start: Node,
        end: Node,
    ): Relationship {
        return when (queryDirection) {
            QueryDirection.DIRECTED -> when (direction) {
                Direction.IN -> end.relationshipTo(start, relationType)
                Direction.OUT -> start.relationshipTo(end, relationType)
            }

            QueryDirection.UNDIRECTED -> start.relationshipBetween(end, relationType)
        }
    }

}
