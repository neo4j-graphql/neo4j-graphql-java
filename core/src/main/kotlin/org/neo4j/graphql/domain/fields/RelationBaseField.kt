package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.naming.RelationshipBaseNames
import org.neo4j.graphql.domain.predicates.RelationOperator
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition

sealed class RelationBaseField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
) : BaseField(
    fieldName,
    type,
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

    override fun getRequiredNode(name: String) = getNode(name)
        ?: throw IllegalArgumentException("unknown implementation $name for ${this.getOwnerName()}.$fieldName")

    override fun getNode(name: String) = extractOnTarget(
        onNode = { it.takeIf { it.name == name } },
        onInterface = { it.getRequiredNode(name) },
        onUnion = { it.getRequiredNode(name) }
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
            RelationOperator.entries.forEach { op ->
                if (op.list == this.isList()) {
                    val name = (this.fieldName.takeIf { !isConnection } ?: connectionField.fieldName) +
                            (op.suffix?.let { "_$it" } ?: "")
                    result[name] = RelationPredicateDefinition(name, this, op, isConnection)
                }
            }
        }
        result
    }
}
