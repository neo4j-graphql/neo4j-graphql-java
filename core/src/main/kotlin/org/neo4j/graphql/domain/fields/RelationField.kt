package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.predicates.RelationOperator
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.isList

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
    var unionNodeNames: List<String>,
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
), NodeResolver {

    // TODO move to connection field?
    val relationshipTypeName: String get() = "${connectionPrefix}${fieldName.capitalize()}Relationship"

    var union: Union? = null

    lateinit var connectionField: ConnectionField

    /**
     * The referenced node, if the relationship is not an interface nor a union
     */
    var node: Node? = null

    val isInterface: Boolean get() = interfaze != null
    val isUnion: Boolean get() = unionNodeNames.isNotEmpty()

    val predicates: Map<String, RelationPredicateDefinition> by lazy {
        val result = mutableMapOf<String, RelationPredicateDefinition>()
        listOf(true, false).forEach { isConnection ->
            RelationOperator.values().forEach { op ->
                // TODO https://github.com/neo4j/graphql/issues/144
//                if (op.list == this.typeMeta.type.isList()) {
                    val name = (this.fieldName.takeIf { !isConnection } ?: connectionField.fieldName) +
                            (op.suffix?.let { "_$it" } ?: "")
                    result[name] = RelationPredicateDefinition(name, this, op, isConnection)
//                }
            }
        }
        result
    }

    fun getImplementingType(): ImplementingType? = node ?: interfaze

    override fun getRequiredNode(name: String) = getNode(name)
        ?: throw IllegalArgumentException("unknown implementation $name for ${this.getOwnerName()}.$fieldName")

    override fun getNode(name: String) = extractOnTarget(
        onNode = { it.takeIf { it.name == name } },
        onInterface = { it.getRequiredImplementation(name) },
        onUnion = { it.getRequiredNode(name) }
    )

    fun getReferenceNodes() = extractOnTarget(
        onNode = { listOf(it) },
        onInterface = { it.implementations.values },
        onUnion = { it.nodes.values }
    )

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
        Direction.IN -> end.relationshipTo(start, relationType)
        Direction.OUT -> start.relationshipTo(end, relationType)
    }.let { if (name != null) it.named(name.resolveName()) else it }

    fun <UNION_RESULT : RESULT, INTERFACE_RESULT : RESULT, NODE_RESULT : RESULT, RESULT> extractOnTarget(
        onNode: (Node) -> NODE_RESULT,
        onInterface: (Interface) -> INTERFACE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT {
        node?.let { return onNode(it) }
        interfaze?.let { return onInterface(it) }
        union?.let { return onUnion(it) }
        throw IllegalStateException("type of ${getOwnerName()}.${fieldName} is neither node nor interface nor union")
    }

    fun <UNION_RESULT : RESULT, IMPLEMENTATION_TYPE_RESULT : RESULT, RESULT> extractOnTarget(
        onImplementingType: (ImplementingType) -> IMPLEMENTATION_TYPE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT = extractOnTarget(
        onNode = { onImplementingType(it) },
        onInterface = { onImplementingType(it) },
        onUnion
    )
}
