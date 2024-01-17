package org.neo4j.graphql.schema.model.inputs

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.FieldSuffix
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.schema.model.inputs.WhereInput.Augmentation.addNestingWhereFields
import org.neo4j.graphql.schema.model.inputs.aggregation.AggregateInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

interface WhereInput {

    class UnionWhereInput(union: Union, data: Dict) : WhereInput,
        PerNodeInput<NodeWhereInput>(union, data, { node, value -> NodeWhereInput(node, value.toDict()) }) {

        object Augmentation : AugmentationBase {
            fun generateWhereIT(union: Union, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType("${union.name}${Constants.InputTypeSuffix.Where}") { fields, _ ->
                    union.nodes.values.forEach { node ->
                        NodeWhereInput.Augmentation
                            .generateWhereIT(node, ctx)?.let { fields += ctx.inputValue(node.name, it.asType()) }
                    }
                }
        }

    }

    class NodeWhereInput(
        node: Node,
        data: Dict
    ) : WhereInput,
        FieldContainerWhereInput<NodeWhereInput>(data, node, { NodeWhereInput(node, it) }) {
        object Augmentation : AugmentationBase {

            fun generateWhereIT(node: Node, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.Where}") { fields, _ ->

                    fields += FieldContainerWhereInput.Augmentation
                        .getWhereFields(node.name, node.fields, ctx)

                }
        }
    }

    class EdgeWhereInput(
        relationshipProperties: RelationshipProperties,
        data: Dict
    ) : FieldContainerWhereInput<EdgeWhereInput>(
        data,
        relationshipProperties,
        { EdgeWhereInput(relationshipProperties, it) }) {
        object Augmentation : AugmentationBase {

            // TODO rename to edge
            fun generateRelationPropertiesWhereIT(properties: RelationshipProperties?, ctx: AugmentationContext) =
                if (properties == null) {
                    null
                } else {
                    ctx.getOrCreateInputObjectType(properties.interfaceName + Constants.InputTypeSuffix.Where) { fields, _ ->
                        fields += FieldContainerWhereInput.Augmentation
                            .getWhereFields(properties.interfaceName, properties.fields, ctx)
                    }
                }
        }
    }


    class InterfaceWhereInput(
        interfaze: Interface,
        val data: Dict
    ) : FieldContainerWhereInput<InterfaceWhereInput>(data, interfaze, { InterfaceWhereInput(interfaze, it) }) {

        //TODO remove
        val on = data.nestedDict(Constants.ON)?.let {
            PerNodeInput(interfaze, it, { node, value -> NodeWhereInput(node, value.toDict()) })
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
            val overrideData = data.nestedDict(Constants.ON) ?: emptyMap()
            return NodeWhereInput(node, Dict(data + overrideData))
        }

        object Augmentation : AugmentationBase {
            fun generateFieldWhereIT(interfaze: Interface, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType("${interfaze.name}${Constants.InputTypeSuffix.Where}") { fields, _ ->

                    if (ctx.schemaConfig.experimental) {
                        ctx.addTypenameEnum(interfaze, fields)
                    } else {
                        ctx.addOnField(interfaze,
                            Constants.InputTypeSuffix.ImplementationsWhere,
                            fields,
                            asList = false,
                            { node -> NodeWhereInput.Augmentation.generateWhereIT(node, ctx) })
                    }


                    fields += FieldContainerWhereInput.Augmentation.getWhereFields(
                        interfaze.name,
                        interfaze.fields,
                        ctx,
                        isInterface = true
                    )

                }

        }
    }

    sealed class FieldContainerWhereInput<T : FieldContainerWhereInput<T>>(
        data: Dict,
        fieldContainer: FieldContainer<*>,
        nestedWhereFactory: (data: Dict) -> T?
    ) : WhereInput, NestedWhere<T>(data, nestedWhereFactory) {

        val predicates = data
            .filterNot { SPECIAL_KEYS.contains(it.key) }
            .mapNotNull { (key, value) -> createPredicate(fieldContainer, key, value) }


        val relationAggregate: Map<RelationField, AggregateInput> = data
            .mapNotNull { (key, value) ->
                val field = fieldContainer.relationAggregationFields[key] ?: return@mapNotNull null
                field to (AggregateInput.create(field, value) ?: return@mapNotNull null)
            }
            .toMap()

        fun hasPredicates(): Boolean = predicates.isNotEmpty()
                || nestedConditions.values.flatten().find { it.hasPredicates() } != null

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

        object Augmentation : AugmentationBase {
            fun getWhereFields(
                typeName: String,
                fieldList: List<BaseField>,
                ctx: AugmentationContext,
                isInterface: Boolean = false,
                whereName: String = "${typeName}${Constants.InputTypeSuffix.Where}"
            ): List<InputValueDefinition> {
                val result = mutableListOf<InputValueDefinition>()
                fieldList.forEach { field ->
                    if (field is ScalarField && field.isFilterableByValue()) {
                        field.predicates.values.forEach {
                            result += inputValue(it.name, it.type) {
                                (field.deprecatedDirective ?: it.deprecated?.toDeprecatedDirective())
                                    ?.let { directive(it) }
                            }
                        }
                    }
                    if (field.annotations.relayId != null) {
                        result += inputValue(Constants.ID_FIELD, Constants.Types.ID)
                    }
                    if (field is RelationField) {
                        if ((field.node != null || (field.union != null && ctx.schemaConfig.experimental))
//                            && !isInterface
                        ) {
                            // TODO REVIEW Darrell why not for union or interfaces https://github.com/neo4j/graphql/issues/810
                            if (field.isFilterableByValue()) {
                                WhereInput.Augmentation.generateWhereOfFieldIT(field, ctx)?.let { where ->
                                    field.predicates.values.filterNot { it.connection }.forEach { predicateDefinition ->
                                        result += inputValue(predicateDefinition.name, where.asType()) {
                                            (field.deprecatedDirective ?: predicateDefinition.deprecated)
                                                ?.let { directive(it) }
                                            predicateDefinition.description?.let { description(it) }
                                        }
                                    }
                                }
                            }
                            if (field.node != null) {
                                AggregateInput.Augmentation.generateAggregateInputIT(typeName, field, ctx)?.let {
                                    result += inputValue(field.fieldName + FieldSuffix.Aggregate, it.asType()) {
                                        field.deprecatedDirective?.let { directive(it) }
                                    }
                                }
                            }
                        }
                    }
                    if (field is ConnectionField && field.isFilterableByValue()) {
                        ConnectionWhere.Augmentation.generateConnectionWhereIT(field, ctx)?.let { where ->
                            field.relationshipField.predicates.values.filter { it.connection }
                                .forEach { predicateDefinition ->
                                    result += inputValue(predicateDefinition.name, where.asType()) {
                                        (field.deprecatedDirective ?: predicateDefinition.deprecated)
                                            ?.let { directive(it) }
                                        predicateDefinition.description?.let { description(it) }
                                    }
                                }
                        }
                    }
                }
                addNestingWhereFields(whereName, result, ctx, isInterface)
                return result
            }
        }
    }

    companion object {

        fun create(fieldContainer: FieldContainer<*>?, data: Dict): FieldContainerWhereInput<*>? =
            when (fieldContainer) {
                is Node -> NodeWhereInput(fieldContainer, data)
                is Interface -> InterfaceWhereInput(fieldContainer, data)
                is RelationshipProperties -> EdgeWhereInput(fieldContainer, data)
                null -> null
            }

        fun create(field: RelationField, data: Dict) = field.extractOnTarget(
            onImplementingType = { create(it, data) },
            onUnion = { UnionWhereInput(it, data) },
        )

        fun createPredicate(fieldContainer: FieldContainer<*>, key: String, value: Any?) =
            fieldContainer.predicates[key]?.let { def ->
                when (def) {
                    is ScalarPredicateDefinition -> ScalarFieldPredicate(def, value)
                    is RelationPredicateDefinition -> when (def.connection) {
                        true -> ConnectionFieldPredicate(def, value?.let { ConnectionWhere.create(def.field, it) })
                        false -> RelationFieldPredicate(def, value?.let { create(def.field, it.toDict()) })
                    }

                    is AggregationPredicateDefinition -> AggregationFieldPredicate(def, value)
                }
            }
    }

    object Augmentation : AugmentationBase {
        fun generateWhereOfFieldIT(field: RelationField, ctx: AugmentationContext): String? =
            ctx.getTypeFromRelationField(field, RelationFieldBaseAugmentation::generateFieldWhereIT)

        fun generateWhereIT(entity: Entity, ctx: AugmentationContext): String? = entity
            .extractOnTarget(
                { node -> NodeWhereInput.Augmentation.generateWhereIT(node, ctx) },
                { interfaze -> InterfaceWhereInput.Augmentation.generateFieldWhereIT(interfaze, ctx) },
                { union -> UnionWhereInput.Augmentation.generateWhereIT(union, ctx) }
            )

        fun addNestingWhereFields(
            name: String,
            fields: MutableList<InputValueDefinition>,
            ctx: AugmentationContext,
            isInterface: Boolean = false
        ) {
            if (fields.isNotEmpty() && (!isInterface || ctx.schemaConfig.experimental)) {
                val listType = ListType(name.asRequiredType())
                fields += inputValue(Constants.OR, listType)
                fields += inputValue(Constants.AND, listType)
                fields += inputValue(Constants.NOT, name.asType())
            }
        }
    }
}
