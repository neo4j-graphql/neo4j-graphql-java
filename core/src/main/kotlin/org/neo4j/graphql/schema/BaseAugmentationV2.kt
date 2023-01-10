package org.neo4j.graphql.schema

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.FieldSuffix
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.field_arguments.AggregateFieldInputArgs
import org.neo4j.graphql.domain.inputs.field_arguments.ConnectionFieldInputArgs
import org.neo4j.graphql.domain.inputs.field_arguments.RelationFieldInputArgs

open class BaseAugmentationV2(
    val ctx: AugmentationContext,
) : AugmentationBase by ctx {

    fun generateNodeOT(node: Node) = getOrCreateObjectType(node.name,
        init = {
            description(node.description)
            comments(node.comments)
            directives(node.otherDirectives)
            implementz(node.interfaces.map { it.name.asType() })
        },
        initFields = { fields, _ ->
            node.fields
                .filterNot { it.writeonly }
                .forEach { field ->
                    fields += mapField(field)
                    (field as? RelationField)?.let { relationField ->
                        generateAggregationSelectionOT(relationField)?.let { aggr ->
                            fields += field(relationField.fieldName + FieldSuffix.Aggregate, aggr.asType()) {

                                AggregateFieldInputArgs.Augmentation
                                    .getFieldArguments(relationField, ctx)
                                    .let { inputValueDefinitions(it) }

                                directives(relationField.deprecatedDirectives)
                            }
                        }

                    }
                }
        }
    )

    fun generateNodeConnectionOT(node: Node): String {
        val name = "${node.plural.capitalize()}${Constants.OutputTypeSuffix.Connection}"

        return getOrCreateObjectType(name)
        { fields, _ ->
            generateNodeEdgeOT(node).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
            ?: throw IllegalStateException("Expected $name to have fields")
    }

    private fun generateNodeEdgeOT(node: Node): String =
        getOrCreateObjectType("${node.name}${Constants.OutputTypeSuffix.Edge}") { fields, _ ->
            fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
            fields += field(Constants.NODE_FIELD, node.name.asType(true))
        }
            ?: throw IllegalStateException("Expected ${node.name}Edge to have fields")

    fun generateInterfaceType(interfaze: Interface) {
        ctx.typeDefinitionRegistry.replace(InterfaceTypeDefinition.newInterfaceTypeDefinition()
            .apply {
                name(interfaze.name)
                description(interfaze.description)
                comments(interfaze.comments)
                directives(interfaze.otherDirectives)
                implementz(interfaze.interfaces.map { it.name.asType() })
                definitions(interfaze.fields.filterNot { it.writeonly }
                    .map { mapField(it) })
            }
            .build()
        )
    }

    fun generateNodeFulltextOT(node: Node) = getOrCreateObjectType("${node.name}FulltextResult", {
        description("The result of a fulltext search on an index of ${node.name}".asDescription())
    }) { fields, _ ->
        fields += field(node.name.lowercase(), node.name.asType(true))
        fields += field(Constants.SCORE, Constants.Types.Float.makeRequired())
    }


    private fun mapField(field: BaseField): FieldDefinition {
        val args = field.arguments.toMutableList()
        val type = when (field) {
            is ConnectionField -> generateConnectionOT(field).wrapLike(field.typeMeta.type)
            else -> field.typeMeta.type
        }

        // TODO https://github.com/neo4j/graphql/issues/869
        if (field is RelationField) {
            args += RelationFieldInputArgs.Augmentation.getFieldArguments(field, ctx)
        } else if (field is ConnectionField) {
            args += ConnectionFieldInputArgs.Augmentation.getFieldArguments(field, ctx)
        }


        return field(field.fieldName, type) {
            description(field.description)
            comments(field.comments)
            directives(field.otherDirectives)
            inputValueDefinitions(args)
        }
    }

    private fun generateConnectionOT(field: ConnectionField) =
        getOrCreateObjectType(field.typeMeta.type.name()) { fields, _ ->
            generateRelationshipOT(field).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
            ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")


    private fun generateRelationshipOT(field: ConnectionField): String =
        getOrCreateObjectType(field.relationshipTypeName,
            init = {
                field.properties?.let { implementz(it.interfaceName.asType()) }
//                (field.owner as? Node)?.interfaces?.forEach { interfaze ->
//                    interfaze.fields.filterIsInstance<ConnectionField>()
//                        .find { it.fieldName == field.fieldName }
//                        ?.let { generateRelationshipOT(it) }
//                        ?.let { implementz(it.asType()) }
//                }
            },
            initFields = { fields, _ ->
                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
                fields += field(Constants.NODE_FIELD, NonNullType(field.relationshipField.typeMeta.type.inner()))
                fields += field.properties?.fields?.map { mapField(it) } ?: emptyList()
            })
            ?: throw IllegalStateException("Expected ${field.relationshipTypeName} to have fields")


    private fun generateAggregationSelectionOT(rel: RelationField): String? {
        val refNode = rel.node ?: return null
        val aggregateTypeNames = rel.aggregateTypeNames ?: return null
        return getOrCreateObjectType(aggregateTypeNames.field) { fields, _ ->
            fields += field(Constants.COUNT, Constants.Types.Int.makeRequired())
            createAggregationField(aggregateTypeNames.node, refNode.fields)
                ?.let { fields += field(Constants.NODE_FIELD, it.asType()) }
            createAggregationField(aggregateTypeNames.edge, rel.properties?.fields ?: emptyList())
                ?.let { fields += field(Constants.EDGE_FIELD, it.asType()) }
        } ?: throw IllegalStateException("Expected at least the count field")
    }

    private fun createAggregationField(name: String, relFields: List<BaseField>) =
        getOrCreateObjectType(name) { fields, _ ->
            relFields
                .filterIsInstance<PrimitiveField>()
                .filterNot { it.typeMeta.type.isList() }
                .forEach { field ->
                    getAggregationSelectionLibraryType(field)
                        ?.let { fields += field(field.fieldName, NonNullType(it)) }
                }
        }

    protected fun getAggregationSelectionLibraryType(field: PrimitiveField): Type<*>? {
        val name = field.getAggregationSelectionLibraryTypeName()
        ctx.neo4jTypeDefinitionRegistry.getUnwrappedType(name) ?: return null
        return TypeName(name)
    }
}
