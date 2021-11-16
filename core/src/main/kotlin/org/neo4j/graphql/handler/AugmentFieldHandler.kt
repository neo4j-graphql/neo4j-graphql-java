package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase

/**
 * This class augments existing fields on a type and adds filtering and sorting to these fields.
 */
class AugmentFieldHandler(
        schemaConfig: SchemaConfig,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

    override fun augmentType(type: ImplementingTypeDefinition<*>) {
        val enhanceRelations = { type.fieldDefinitions.map { fieldDef -> fieldDef.transform { augmentRelation(it, fieldDef) } } }

        val rewritten = when (type) {
            is ObjectTypeDefinition -> type.transform { it.fieldDefinitions(enhanceRelations()) }
            is InterfaceTypeDefinition -> type.transform { it.definitions(enhanceRelations()) }
            else -> return
        }

        typeDefinitionRegistry.remove(rewritten.name, rewritten)
        typeDefinitionRegistry.add(rewritten)
    }

    private fun augmentRelation(fieldBuilder: FieldDefinition.Builder, field: FieldDefinition) {
        if (!field.isRelationship() || !field.type.isList() || field.isIgnored()) {
            return
        }

        val fieldType = field.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return

        if (schemaConfig.queryOptionStyle == SchemaConfig.InputStyle.INPUT_TYPE) {

            val optionsTypeName = addOptions(fieldType)
            if (field.inputValueDefinitions.find { it.name == ProjectionBase.OPTIONS } == null) {
                fieldBuilder.inputValueDefinition(input(ProjectionBase.OPTIONS, TypeName(optionsTypeName)))
            }

        } else {

            if (field.inputValueDefinitions.find { it.name == ProjectionBase.FIRST } == null) {
                fieldBuilder.inputValueDefinition(input(ProjectionBase.FIRST, TypeInt))
            }
            if (field.inputValueDefinitions.find { it.name == ProjectionBase.OFFSET } == null) {
                fieldBuilder.inputValueDefinition(input(ProjectionBase.OFFSET, TypeInt))
            }
            if (field.inputValueDefinitions.find { it.name == ProjectionBase.ORDER_BY } == null) {
                addOrdering(fieldType)?.let { orderingTypeName ->
                    val orderType = ListType(NonNullType(TypeName(orderingTypeName)))
                    fieldBuilder.inputValueDefinition(input(ProjectionBase.ORDER_BY, orderType))
                }
            }
            if (!schemaConfig.useWhereFilter && schemaConfig.query.enabled && !schemaConfig.query.exclude.contains(fieldType.name)) {
                // legacy support
                val relevantFields = fieldType
                    .getScalarFields()
                    .filter { scalarField -> field.inputValueDefinitions.find { it.name == scalarField.name } == null }
                    .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
                getInputValueDefinitions(relevantFields, true, { true }).forEach {
                    fieldBuilder.inputValueDefinition(it)
                }
            }
        }

        val filterFieldName = if (schemaConfig.useWhereFilter) ProjectionBase.WHERE else ProjectionBase.FILTER
        if (schemaConfig.query.enabled && !schemaConfig.query.exclude.contains(fieldType.name) && field.inputValueDefinitions.find { it.name == filterFieldName } == null) {
            val filterTypeName = addFilterType(fieldType)
            fieldBuilder.inputValueDefinition(input(filterFieldName, TypeName(filterTypeName)))
        }

    }

    override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? = null
}

