package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.naming.RelationshipOperations
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
    annotations: Annotations,
    val properties: RelationshipProperties?,
    /**
     * The node or interface name. If the filed is defined in an interface, the prefix will have the interface's name
     */
    // TODO remove?
    val connectionPrefix: String,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), NodeResolver {

    val relationship get() = requireNotNull(annotations.relationship)

    /**
     * The type of the neo4j relation
     */
    val relationType get() = relationship.type
    val direction get() = relationship.direction
    val queryDirection get() = relationship.queryDirection

    // TODO move to connection field?
    val relationshipTypeName: String get() = "${connectionPrefix}${fieldName.capitalize()}Relationship"

    lateinit var connectionField: ConnectionField

    lateinit var target: Entity
    val operations = RelationshipOperations(this, annotations)
    val isInterface: Boolean get() = target is Interface
    val isUnion: Boolean get() = target is Union

    val inheritedFrom by lazy {
        (owner as ImplementingType)
            .interfaces
            .firstOrNull { it.getField(fieldName) != null }
            ?.name
    }

    val predicates: Map<String, RelationPredicateDefinition> by lazy {
        val result = mutableMapOf<String, RelationPredicateDefinition>()
        listOf(true, false).forEach { isConnection ->
            RelationOperator.values().forEach { op ->
                if (op.list == this.typeMeta.type.isList()) {
                    val name = (this.fieldName.takeIf { !isConnection } ?: connectionField.fieldName) +
                            (op.suffix?.let { "_$it" } ?: "")
                    result[name] = RelationPredicateDefinition(name, this, op, isConnection)
                }
            }
        }
        result
    }

    val implementingType get() = target as? ImplementingType
    val node get() = target as? Node
    val union get() = target as? Union
    val interfaze get() = target as? Interface

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
            return createDslRelation(start, end, name, startLeft)
        }
        return start.relationshipBetween(end, relationType)
            .let { if (name != null) it.named(name.resolveName()) else it }
    }

    fun createDslRelation(
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        name: String? = null,
        startLeft: Boolean = false, // TODO used only to make migrating form JS easier
    ): Relationship = when (direction) {
        Direction.IN -> if (startLeft) {
            start.relationshipFrom(end, relationType)
        } else {
            end.relationshipTo(start, relationType)
        }

        Direction.OUT -> start.relationshipTo(end, relationType)
    }.let { if (name != null) it.named(name) else it }

    fun createDslRelation(
        start: org.neo4j.cypherdsl.core.Node,
        end: org.neo4j.cypherdsl.core.Node,
        name: ChainString?,
        startLeft: Boolean = false, // TODO used only to make migrating form JS easier
    ): Relationship = createDslRelation(start, end, name?.resolveName(), startLeft)

    fun <UNION_RESULT : RESULT, INTERFACE_RESULT : RESULT, NODE_RESULT : RESULT, RESULT> extractOnTarget(
        onNode: (Node) -> NODE_RESULT,
        onInterface: (Interface) -> INTERFACE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT = target.extractOnTarget(onNode, onInterface, onUnion)

    fun <UNION_RESULT : RESULT, IMPLEMENTATION_TYPE_RESULT : RESULT, RESULT> extractOnTarget(
        onImplementingType: (ImplementingType) -> IMPLEMENTATION_TYPE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT = extractOnTarget(
        onNode = { onImplementingType(it) },
        onInterface = { onImplementingType(it) },
        onUnion
    )

    fun shouldGenerateFieldInputType(implementingType: ImplementingType): Boolean {
        return relationship.isConnectAllowed ||
                relationship.isCreateAllowed ||
                relationship.isConnectOrCreateAllowed ||
                implementingType is Node && implementingType.uniqueFields.isNotEmpty()
    }


    fun shouldGenerateUpdateFieldInputType(implementingType: ImplementingType): Boolean {
        val onlyConnectOrCreate = relationship.isOnlyConnectOrCreate
        val hasNestedOperationsAllowed = relationship.nestedOperations.isNotEmpty()
        if (target is Interface) {
            return hasNestedOperationsAllowed && !onlyConnectOrCreate
        }
        if (target is Union) {
            require(implementingType is Node) { "Expected member entity" }
            val onlyConnectOrCreateAndNoUniqueFields = onlyConnectOrCreate && implementingType.uniqueFields.isEmpty()
            return hasNestedOperationsAllowed && !onlyConnectOrCreateAndNoUniqueFields
        }
        val onlyConnectOrCreateAndNoUniqueFields = onlyConnectOrCreate && implementingType.uniqueFields.isEmpty()
        return hasNestedOperationsAllowed && !onlyConnectOrCreateAndNoUniqueFields
    }
}
