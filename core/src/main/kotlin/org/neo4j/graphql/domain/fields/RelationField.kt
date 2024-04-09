package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.naming.RelationshipNames
import org.neo4j.graphql.handler.utils.ChainString

/**
 * Representation of the `@relationship` directive and its meta.
 */
class RelationField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    override val properties: RelationshipProperties?,
    connectionPrefix: String,
) : RelationBaseField(
    fieldName,
    typeMeta,
    annotations,
    connectionPrefix
) {

    override val namings = RelationshipNames(this, annotations)

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

    val inheritedFrom by lazy {
        (owner as ImplementingType)
            .interfaces
            .firstOrNull { it.getField(fieldName) != null }
            ?.name
    }

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
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        directed: Boolean?,
        name: ChainString? = null,
        startLeft: Boolean = false, // TODO used only to make migrating form JS easier
    ): Relationship {
        val useDirected = when (queryDirection) {
            QueryDirection.DEFAULT_DIRECTED -> directed ?: true
            QueryDirection.DEFAULT_UNDIRECTED -> directed ?: false
            QueryDirection.DIRECTED_ONLY -> {
                check(directed == true, { "Invalid direction in 'DIRECTED_ONLY' relationship" })
                true
            }

            QueryDirection.UNDIRECTED_ONLY -> {
                check(directed == false, { "Invalid direction in 'UNDIRECTED_ONLY' relationship" })
                false
            }
        }
        if (useDirected) {
            return createDslRelation(start, end, name)
        }
        return start.relationshipBetween(end, relationType)
            .let { if (name != null) it.named(name.resolveName()) else it }
    }

    fun createDslRelation(
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        name: String? = null,
    ): Relationship = when (direction) {
        Direction.IN -> end.relationshipTo(start, relationType)
        Direction.OUT -> start.relationshipTo(end, relationType)
    }.let { if (name != null) it.named(name) else it }

    fun createDslRelation(
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        name: ChainString?,
    ): Relationship = createDslRelation(start, end, name?.resolveName())
}
