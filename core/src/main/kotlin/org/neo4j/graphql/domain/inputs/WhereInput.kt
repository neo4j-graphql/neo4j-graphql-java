package org.neo4j.graphql.domain.inputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.aggregation.AggregateInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.wrapList

interface WhereInput {

    class UnionWhereInput(union: Union, data: Dict) : WhereInput,
        PerNodeInput<NodeWhereInput>(union, data, { node, value -> NodeWhereInput(node, Dict(value)) })

    class NodeWhereInput(
        node: Node,
        data: Dict
    ) : WhereInput,
        FieldContainerWhereInput<NodeWhereInput>(data, node, { NodeWhereInput(node, it) })

    class PropertyWhereInput(
        relationshipProperties: RelationshipProperties,
        data: Dict
    ) : WhereInput,
        FieldContainerWhereInput<PropertyWhereInput>(
            data,
            relationshipProperties,
            { PropertyWhereInput(relationshipProperties, it) })


    class InterfaceWhereInput(
        interfaze: Interface,
        val data: Dict
    ) : WhereInput,
        FieldContainerWhereInput<InterfaceWhereInput>(data, interfaze, { InterfaceWhereInput(interfaze, it) }) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node, value -> NodeWhereInput(node, Dict(value)) }
            )
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeWhereInput)

        /**
         * We want to filter nodes if there is either:
         *   - There is at least one root filter in addition to _on
         *   - There is no _on filter
         *   - `_on` is the only filter and the current implementation can be found within it
         */
        override fun hasFilterForNode(node: Node): Boolean {
            val hasRootFilter = hasPredicates()
            return (hasRootFilter || on == null || on.hasNodeData(node))
        }

        override fun withPreferredOn(node: Node): NodeWhereInput {
            val overrideData = data[Constants.ON]?.let { Dict(it) } ?: emptyMap()
            return NodeWhereInput(node, Dict(data + overrideData))
        }
    }

    sealed class FieldContainerWhereInput<T : FieldContainerWhereInput<T>>(
        data: Dict,
        fieldContainer: FieldContainer<*>,
        nestedWhereFactory: (data: Dict) -> T?
    ) : WhereInput, NestedWhere<T>(data, nestedWhereFactory) {

        val predicates = data
            .filter { !SPECIAL_KEYS.contains(it.key) }
            .mapNotNull { (key, value) -> createPredicate(fieldContainer, key, value) }


        val aggregate: Map<RelationField, AggregateInput> = data
            .mapNotNull { (key, value) ->
                val field = fieldContainer.aggregationPredicates[key] ?: return@mapNotNull null
                field to (AggregateInput.create(field, value) ?: return@mapNotNull null)
            }
            .toMap()

        fun hasPredicates(): Boolean = predicates.isNotEmpty()
                || or?.find { it.hasPredicates() } != null
                || and?.find { it.hasPredicates() } != null

        /**
         * We want to filter nodes if there is either:
         *   - There is at least one root filter in addition to _on
         *   - There is no _on filter
         *   - `_on` is the only filter and the current implementation can be found within it
         */
        open fun hasFilterForNode(node: Node): Boolean = hasPredicates()

        open fun withPreferredOn(node: Node): WhereInput = this

        companion object {
            val SPECIAL_KEYS = setOf(Constants.ON, Constants.AND, Constants.OR)
        }
    }

    companion object {

        fun create(fieldContainer: FieldContainer<*>?, data: Any): FieldContainerWhereInput<*>? =
            when (fieldContainer) {
                is Node -> NodeWhereInput(fieldContainer, Dict(data))
                is Interface -> InterfaceWhereInput(fieldContainer, Dict(data))
                is RelationshipProperties -> PropertyWhereInput(fieldContainer, Dict(data))
                null -> null
                else -> error("unsupported type for FieldContainer: " + fieldContainer.javaClass.name)
            }

        fun create(field: RelationField, data: Any) = field.extractOnTarget(
            onImplementingType = { create(it, data) },
            onUnion = { UnionWhereInput(it, Dict(data)) },
        )

        fun createPredicate(fieldContainer: FieldContainer<*>, key: String, value: Any?) =
            fieldContainer.getPredicate(key)
                ?.let { def ->
                    when (def) {
                        is ScalarPredicateDefinition -> ScalarFieldPredicate(def, value)
                        is RelationPredicateDefinition -> when (def.connection) {
                            true -> RelationFieldPredicate(def, value?.let { create(def.field, it) })
                            false -> ConnectionFieldPredicate(def, value?.let { ConnectionWhere.create(def.field, it) })
                        }

                        else -> null
                    }
                }
    }
}
