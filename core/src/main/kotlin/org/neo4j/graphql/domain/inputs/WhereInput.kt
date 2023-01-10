package org.neo4j.graphql.domain.inputs

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.FieldSuffix
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.aggregation.AggregateInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.schema.InterfaceAugmentation
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

interface WhereInput {

    class UnionWhereInput(union: Union, data: Dict) : WhereInput,
        PerNodeInput<NodeWhereInput>(union, data, { node, value -> NodeWhereInput(node, Dict(value)) })

    class NodeWhereInput(
        node: Node,
        data: Dict
    ) : WhereInput,
        FieldContainerWhereInput<NodeWhereInput>(data, node, { NodeWhereInput(node, it) }) {
        object Augmentation {

            fun generateWhereIT(node: Node, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.Where}") { fields, _ ->

                    fields += FieldContainerWhereInput.Augmentation
                        .getWhereFields(node.name, node.fields, ctx, plural = node.pluralKeepCase)

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
        object Augmentation {

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

        object Augmentation {
            fun generateFieldWhereIT(interfaze: Interface, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType("${interfaze.name}${Constants.InputTypeSuffix.Where}") { fields, _ ->

                    InterfaceAugmentation(interfaze, ctx)
                        .addOnField(Constants.InputTypeSuffix.ImplementationsWhere, fields, asList = false,
                            { node -> NodeWhereInput.Augmentation.generateWhereIT(node, ctx) })

                    fields += FieldContainerWhereInput.Augmentation.getWhereFields(
                        interfaze.name,
                        interfaze.fields,
                        ctx,
                        isInterface = true,
                        plural = interfaze.pluralKeepCase
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

        object Augmentation {
            fun getWhereFields(
                typeName: String,
                fieldList: List<BaseField>,
                ctx: AugmentationContext,
                isInterface: Boolean = false,
                plural: String = typeName
            ): List<InputValueDefinition> {
                val result = mutableListOf<InputValueDefinition>()
                fieldList.forEach { field ->
                    if (field is ScalarField) {
                        field.predicates.values.forEach {
                            result += ctx.inputValue(it.name, it.type) {
                                directives(field.deprecatedDirectives)
                            }
                        }
                    }
                    if (field is RelationField) {
                        if (field.node != null) { // TODO REVIEW Darrell why not for union or interfaces https://github.com/neo4j/graphql/issues/810
                            WhereInput.Augmentation.generateWhereOfFieldIT(field, ctx)?.let {
                                if (field.typeMeta.type.isList()) {
                                    // n..m relationship
                                    listOf("ALL", "NONE", "SINGLE", "SOME").forEach { filter ->
                                        result += ctx.inputValue("${field.fieldName}_$filter", it.asType()) {
                                            description(
                                                "Return $plural where ${if (filter !== "SINGLE") filter.lowercase() else "one"} of the related ${field.getImplementingType()?.pluralKeepCase} match this filter".asDescription()
                                            )
                                            directives(field.deprecatedDirectives)
                                        }
                                    }
                                    // TODO remove
                                    result += ctx.inputValue(field.fieldName, it.asType()) {
                                        directive(
                                            Directive(
                                                "deprecated",
                                                listOf(
                                                    Argument(
                                                        "reason",
                                                        StringValue("Use `${field.fieldName}_SOME` instead.")
                                                    )
                                                )
                                            )
                                        )
                                    }
                                    result += ctx.inputValue(
                                        field.fieldName + "_NOT",
                                        it.asType()
                                    ) {
                                        directive(
                                            Directive(
                                                "deprecated",
                                                listOf(
                                                    Argument(
                                                        "reason",
                                                        StringValue("Use `${field.fieldName}_NONE` instead.")
                                                    )
                                                )
                                            )
                                        )
                                    }
                                } else {
                                    // n..1 relationship
                                    result += ctx.inputValue(field.fieldName, it.asType())
                                    result += ctx.inputValue(field.fieldName + "_NOT", it.asType())
                                }

                            }
                            AggregateInput.Augmentation.generateAggregateInputIT(typeName, field, ctx).let {
                                result += ctx.inputValue(field.fieldName + FieldSuffix.Aggregate, it.asType()) {
                                    directives(field.deprecatedDirectives)
                                }
                            }
                        }
                    }
                    if (field is ConnectionField) {
                        ConnectionWhere.Augmentation.generateConnectionWhereIT(field, ctx)?.let {
                            if (field.relationshipField.typeMeta.type.isList()) {
                                // n..m relationship
                                listOf("ALL", "NONE", "SINGLE", "SOME").forEach { filter ->
                                    result += ctx.inputValue("${field.fieldName}_$filter", it.asType()) {
                                        directives(field.deprecatedDirectives)
                                    }
                                }
                                // TODO remove
                                result += ctx.inputValue(field.fieldName, it.asType()) {
                                    directive(
                                        Directive(
                                            "deprecated",
                                            listOf(
                                                Argument(
                                                    "reason",
                                                    StringValue("Use `${field.fieldName}_SOME` instead.")
                                                )
                                            )
                                        )
                                    )
                                }
                                result += ctx.inputValue(field.fieldName + "_NOT", it.asType()) {
                                    directive(
                                        Directive(
                                            "deprecated",
                                            listOf(
                                                Argument(
                                                    "reason",
                                                    StringValue("Use `${field.fieldName}_NONE` instead.")
                                                )
                                            )
                                        )
                                    )
                                }
                            } else {
                                // n..1 relationship
                                result += ctx.inputValue(field.fieldName, it.asType())
                                result += ctx.inputValue(field.fieldName + "_NOT", it.asType())
                            }
                        }
                    }
                }
                if (!isInterface && result.isNotEmpty()) {
                    val type = ListType("${typeName}${Constants.InputTypeSuffix.Where}".asRequiredType())
                    result += ctx.inputValue(Constants.OR, type)
                    result += ctx.inputValue(Constants.AND, type)
                }
                return result
            }
        }
    }

    companion object {

        fun create(fieldContainer: FieldContainer<*>?, data: Any): FieldContainerWhereInput<*>? =
            when (fieldContainer) {
                is Node -> NodeWhereInput(fieldContainer, Dict(data))
                is Interface -> InterfaceWhereInput(fieldContainer, Dict(data))
                is RelationshipProperties -> EdgeWhereInput(fieldContainer, Dict(data))
                null -> null
                else -> error("unsupported type for FieldContainer: " + fieldContainer.javaClass.name)
            }

        fun create(field: RelationField, data: Any) = field.extractOnTarget(
            onImplementingType = { create(it, data) },
            onUnion = { UnionWhereInput(it, Dict(data)) },
        )

        fun createPredicate(fieldContainer: FieldContainer<*>, key: String, value: Any?) =
            fieldContainer.predicates[key]?.let { def ->
                when (def) {
                    is ScalarPredicateDefinition -> ScalarFieldPredicate(def, value)
                    is RelationPredicateDefinition -> when (def.connection) {
                        true -> ConnectionFieldPredicate(def, value?.let { ConnectionWhere.create(def.field, it) })
                        false -> RelationFieldPredicate(def, value?.let { create(def.field, it) })
                    }

                    is AggregationPredicateDefinition -> AggregationFieldPredicate(def, value)
                    else -> null
                }
            }
    }

    object Augmentation {
        fun generateWhereOfFieldIT(field: RelationField, ctx: AugmentationContext): String? =
            ctx.getTypeFromRelationField(field, RelationFieldBaseAugmentation::generateFieldWhereIT)
    }
}
