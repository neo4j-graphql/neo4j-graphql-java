package org.neo4j.graphql.schema.model.inputs

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.ScalarField
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
                ctx.getOrCreateInputObjectType(union.namings.whereInputTypeName) { fields, _ ->
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
                ctx.getOrCreateInputObjectType(node.namings.whereInputTypeName) { fields, name ->
                    FieldContainerWhereInput.Augmentation.addWhereFields(name, node.fields, ctx, fields)
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
            fun generateRelationPropertiesWhereIT(relationField: RelationBaseField, ctx: AugmentationContext) =
                ctx.getEdgeInputField(relationField, { it.namings.whereInputTypeName }) {
                    ctx.getOrCreateInputObjectType(it.namings.whereInputTypeName) { fields, name ->
                        FieldContainerWhereInput.Augmentation
                            .addWhereFields(name, it.properties?.fields ?: emptyList(), ctx, fields)
                    }
                }
        }
    }


    class InterfaceWhereInput(
        interfaze: Interface,
        val data: Dict
    ) : FieldContainerWhereInput<InterfaceWhereInput>(data, interfaze, { InterfaceWhereInput(interfaze, it) }) {

        //TODO remove
        @Deprecated("Do not use any longer")
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
                ctx.getOrCreateInputObjectType(interfaze.namings.whereInputTypeName) { fields, name ->
                    FieldContainerWhereInput.Augmentation.addWhereFields(
                        name,
                        interfaze.fields,
                        ctx,
                        fields,
                        interfaze,
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
            .mapNotNull { (key, value) -> fieldContainer.createPredicate(key, value) }


        val relationAggregate: Map<RelationBaseField, AggregateInput> = data
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
            val SPECIAL_KEYS = setOf(Constants.ON, Constants.AND, Constants.OR, Constants.NOT)
        }

        object Augmentation : AugmentationBase {
            fun addWhereFields(
                whereName: String,
                fieldList: List<BaseField>,
                ctx: AugmentationContext,
                fields: MutableList<InputValueDefinition>,
                interfaze: Interface? = null
            ) {
                if (interfaze != null) {
                    ctx.addTypenameEnum(interfaze, fields)
                }
                fieldList.forEach { field ->
                    if (field is ScalarField && field.isFilterableByValue()) {
                        field.predicateDefinitions.values.forEach {
                            fields += inputValue(it.name, it.type) {
                                (field.deprecatedDirective ?: it.deprecated?.toDeprecatedDirective())
                                    ?.let { directive(it) }
                            }
                        }
                    }
                    if (field.annotations.relayId != null) {
                        fields += inputValue(Constants.ID_FIELD, Constants.Types.ID)
                    }
                    if (field is RelationBaseField) {
                        if (field.isFilterableByValue()) {
                            WhereInput.Augmentation.generateWhereOfFieldIT(field, ctx)?.let { where ->
                                field.predicateDefinitions.values.filterNot { it.connection }
                                    .forEach { predicateDefinition ->
                                        fields += inputValue(predicateDefinition.name, where.asType()) {
                                            field.deprecatedDirective?.let { directive(it) }
                                            predicateDefinition.description?.let { description(it) }
                                        }
                                    }
                            }
                        }
                        if (field.isFilterableByAggregate()) {
                            if (field.implementingType != null) {
                                // TODO should be field.declarationOrSelf
                                AggregateInput.Augmentation.generateAggregateInputIT(field, ctx)?.let {
                                    fields += inputValue(field.namings.aggregateTypeName, it.asType()) {
                                        field.deprecatedDirective?.let { directive(it) }
                                    }
                                }
                            }
                        }
                    }
                    if (field is ConnectionField && field.isFilterableByValue()) {
                        ConnectionWhere.Augmentation.generateConnectionWhereIT(field, ctx)?.let { where ->
                            field.relationshipField.predicateDefinitions.values.filter { it.connection }
                                .forEach { predicateDefinition ->
                                    fields += inputValue(predicateDefinition.name, where.asType()) {
                                        field.deprecatedDirective?.let { directive(it) }
                                        predicateDefinition.description?.let { description(it) }
                                    }
                                }
                        }
                    }
                }
                addNestingWhereFields(whereName, fields)
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

        fun create(field: RelationBaseField, data: Dict) = field.extractOnTarget(
            onImplementingType = { create(it, data) },
            onUnion = { UnionWhereInput(it, data) },
        )
    }

    object Augmentation : AugmentationBase {
        fun generateWhereOfFieldIT(field: RelationBaseField, ctx: AugmentationContext): String? =
            ctx.getTypeFromRelationField(field, RelationFieldBaseAugmentation::generateFieldWhereIT)

        fun generateWhereIT(entity: Entity, ctx: AugmentationContext): String? = entity
            .extractOnTarget(
                { node -> NodeWhereInput.Augmentation.generateWhereIT(node, ctx) },
                { interfaze -> InterfaceWhereInput.Augmentation.generateFieldWhereIT(interfaze, ctx) },
                { union -> UnionWhereInput.Augmentation.generateWhereIT(union, ctx) }
            )

        fun addNestingWhereFields(name: String, fields: MutableList<InputValueDefinition>) {
            if (fields.isNotEmpty()) {
                val listType = ListType(name.asRequiredType())
                fields += inputValue(Constants.OR, listType)
                fields += inputValue(Constants.AND, listType)
                fields += inputValue(Constants.NOT, name.asType())
            }
        }
    }
}
