package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.handler.utils.ChainString

/**
 * Representation of the `@relationship` directive and its meta.
 */
class RelationField(
    fieldName: String,
    typeMeta: TypeMeta,
    /**
     * If the type of the field is an interface
     */
    val interfaze: Interface?,
    var union: List<String>,
    /**
     * The type of the neo4j relation
     */
    val relationType: String,
    val direction: Direction,
    val queryDirection: QueryDirection,
    val properties: RelationshipProperties?,
    /**
     * The node or interface name. If the filed is defined in an interface, the prefix will have the interface's name
     */
    val connectionPrefix: String,
) : BaseField(
    fieldName,
    typeMeta,
) {

    // TODO move to connection field?
    val relationshipTypeName: String get() = "${connectionPrefix}${fieldName.capitalize()}Relationship"

    /**
     * If the type of the field is an union, this list contains all nodes of the union
     */
    lateinit var unionNodes: List<Node>

    lateinit var connectionField: ConnectionField

    /**
     * The referenced node, if the relationship is not an interface nor a union
     */
    var node: Node? = null

    val isInterface: Boolean get() = interfaze != null
    val isUnion: Boolean get() = union.isNotEmpty()

    fun getImplementingType(): ImplementingType? = node ?: interfaze

    fun getNode(name: String) =
        node?.takeIf { it.name == name }
            ?: unionNodes.firstOrNull { it.name == name }
            ?: interfaze?.implementations?.firstOrNull { it.name == name }

    fun getReferenceNodes() = when {
        interfaze != null -> interfaze.implementations
        unionNodes.isNotEmpty() -> unionNodes
        node != null -> listOf(requireNotNull(node))
        else -> throw IllegalStateException("Type of field ${getOwnerName()}.${fieldName} is neither interface nor union nor simple node")
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

    fun createDslRelation(
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        name: ChainString? = null
    ): Relationship = when (direction) {
        Direction.IN -> start.relationshipFrom(end, relationType)
        Direction.OUT -> start.relationshipTo(end, relationType)
    }.let { if (name != null) it.named(name.resolveName()) else it }
}
