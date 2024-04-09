package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.naming.RelationshipBaseNames
import org.neo4j.graphql.domain.predicates.RelationOperator
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.isList

sealed class RelationBaseField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
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

    lateinit var connectionField: ConnectionField

    lateinit var target: Entity

    abstract val namings: RelationshipBaseNames<*>
    val isInterface: Boolean get() = target is Interface
    val isUnion: Boolean get() = target is Union

    val implementingType get() = target as? ImplementingType
    val node get() = target as? Node
    val union get() = target as? Union
    val interfaze get() = target as? Interface

    open val properties: RelationshipProperties? = null
    val relationshipBaseDirective get() = requireNotNull(annotations.relationshipBaseDirective)

    override fun getRequiredNode(name: String) = getNode(name)
        ?: throw IllegalArgumentException("unknown implementation $name for ${this.getOwnerName()}.$fieldName")

    override fun getNode(name: String) = extractOnTarget(
        onNode = { it.takeIf { it.name == name } },
        onInterface = { it.getRequiredNode(name) },
        onUnion = { it.getRequiredNode(name) }
    )

    fun getReferenceNodes() = extractOnTarget(
        onNode = { listOf(it) },
        onInterface = { it.implementations.values },
        onUnion = { it.nodes.values }
    )

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

    val predicateDefinitions: Map<String, RelationPredicateDefinition> by lazy {
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

    fun shouldGenerateFieldInputType(implementingType: ImplementingType): Boolean {
        return relationshipBaseDirective.isConnectAllowed ||
                relationshipBaseDirective.isCreateAllowed ||
                relationshipBaseDirective.isConnectOrCreateAllowed ||
                implementingType is Node && implementingType.uniqueFields.isNotEmpty()
    }


    fun shouldGenerateUpdateFieldInputType(implementingType: ImplementingType): Boolean {
        val onlyConnectOrCreate = relationshipBaseDirective.isOnlyConnectOrCreate
        val hasNestedOperationsAllowed = relationshipBaseDirective.nestedOperations.isNotEmpty()
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
