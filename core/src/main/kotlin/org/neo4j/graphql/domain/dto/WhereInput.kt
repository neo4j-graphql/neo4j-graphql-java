package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.Predicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

interface WhereInput {

    class UnionWhereInput(data: Map<Node, FieldContainerWhereInput>) : WhereInput, PerNodeInput<FieldContainerWhereInput>(data) {
        companion object {
            fun create(union: Union, data: Any?) =
                create(::UnionWhereInput, union, data,  { node, value -> FieldContainerWhereInput.create(node, value) })
        }
    }


    data class FieldContainerWhereInput(
        override val and: List<FieldContainerWhereInput>? = null,
        override val or: List<FieldContainerWhereInput>? = null,
        val predicates: List<Predicate<*>>? = null,
        val aggregate: Map<RelationField, AggregateInput>? = null,
        val on: ImplementationsWhere? = null,
    ) : WhereInput, NestedWhere<FieldContainerWhereInput> {

        companion object {
            private val SPECIAL_KEYS = setOf(Constants.ON, Constants.AND, Constants.OR)

            fun create(fieldContainer: FieldContainer<*>?, data: Any?): FieldContainerWhereInput? {
                if (fieldContainer == null) return null
                val map = data as? Map<*, *> ?: return null
                val and = map[Constants.AND]?.wrapList()?.mapNotNull { create(fieldContainer, data) }
                val or = map[Constants.OR]?.wrapList()?.mapNotNull { create(fieldContainer, data) }
                val on = data.nestedMap(Constants.ON)?.let {
                    if (fieldContainer !is Interface) {
                        throw IllegalArgumentException("_on is only supported for interfaces")
                    }
                    ImplementationsWhere.create(fieldContainer, it)
                }

                val predicates = mutableListOf<Predicate<*>>()
                val aggregate = mutableMapOf<RelationField, AggregateInput>()

                map.forEach { (key, value) ->
                    if (SPECIAL_KEYS.contains(key) || key !is String) return@forEach

                    val predicate = createPredicate(fieldContainer, key, value)
                    if (predicate != null) {
                        predicates += predicate
                    } else {
                        TODO("Aggregate")
                    }
                }

                return FieldContainerWhereInput(
                    and.takeIf { it?.isNotEmpty() == true },
                    or.takeIf { it?.isNotEmpty() == true },
                    predicates.takeIf { it.isNotEmpty() },
                    aggregate.takeIf { it.isNotEmpty() },
                    on,
                )
            }
        }

        fun excluding(other: FieldContainerWhereInput?): FieldContainerWhereInput {
            val fieldsToExclude = other?.predicates?.map { it.field.fieldName }?.toSet()
                ?: return this
            return copy(predicates = predicates?.filter { fieldsToExclude.contains(it.field.fieldName) })
        }

        fun hasPredicates(): Boolean = !predicates.isNullOrEmpty()
                || or?.find { it.hasPredicates() } != null
                || and?.find { it.hasPredicates() } != null

        /**
         * We want to filter nodes if there is either:
         *   - There is at least one root filter in addition to _on
         *   - There is no _on filter
         *   - `_on` is the only filter and the current implementation can be found within it
         */
        fun hasFilterForNode(node: Node): Boolean {
            val hasRootFilter = hasPredicates()
            return (hasRootFilter || on == null || on.containsKey(node))
        }

        fun withPreferredOn(node: Node): FieldContainerWhereInput {
            val onPredicates = on?.get(node)
                ?.predicates
                ?.map { it.key to it }
                ?.toMap()
                ?: return this
            // If this where key is also inside _on for this implementation, use the one in _on instead
            return copy(predicates = predicates?.map { onPredicates[it.key] ?: it })
        }


    }

    companion object {
        fun create(field: RelationField, data: Any): WhereInput? = field.extractOnTarget(
            onImplementingType = { FieldContainerWhereInput.create(it, data) },
            onUnion = { UnionWhereInput.create(it, data) },
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
