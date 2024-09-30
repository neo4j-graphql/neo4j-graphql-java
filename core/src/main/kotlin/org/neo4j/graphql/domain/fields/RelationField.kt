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
        DEFAULT_DIRECTED,
        DEFAULT_UNDIRECTED,
        DIRECTED_ONLY,
        UNDIRECTED_ONLY,
    }

    fun createQueryDslRelation(
        start: Node,
        end: Node,
        directed: Boolean?,
    ): Relationship {
        val useDirected = when (queryDirection) {
            QueryDirection.DEFAULT_DIRECTED -> directed ?: true
            QueryDirection.DEFAULT_UNDIRECTED -> directed ?: false
            QueryDirection.DIRECTED_ONLY -> {
                check(directed == null || directed == true, { "Invalid direction in 'DIRECTED_ONLY' relationship" })
                true
            }

            QueryDirection.UNDIRECTED_ONLY -> {
                check(directed == null || directed == false, { "Invalid direction in 'UNDIRECTED_ONLY' relationship" })
                false
            }
        }
        if (useDirected) {
            return createDslRelation(start, end)
        }
        return start.relationshipBetween(end, relationType)
    }

    fun createDslRelation(start: Node, end: Node, name: String? = null): Relationship = when (direction) {
        Direction.IN -> end.relationshipTo(start, relationType)
        Direction.OUT -> start.relationshipTo(end, relationType)
    }.let { if (name != null) it.named(name) else it }

}
