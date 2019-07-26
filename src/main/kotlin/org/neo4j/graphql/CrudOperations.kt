package org.neo4j.graphql

import graphql.language.*
import org.neo4j.graphql.handler.*
import org.neo4j.graphql.handler.BaseDataFetcher.Companion.getInputValueDefinitions
import org.neo4j.graphql.handler.BaseDataFetcher.Companion.input
import org.neo4j.graphql.handler.relation.CreateRelationHandler
import org.neo4j.graphql.handler.relation.CreateRelationTypeHandler
import org.neo4j.graphql.handler.relation.DeleteRelationHandler

@Deprecated(message = "this is no longer needed since the tests are refactored")
data class Augmentation(
        var create: BaseDataFetcher? = null,
        var merge: BaseDataFetcher? = null,
        var update: BaseDataFetcher? = null,
        var delete: BaseDataFetcher? = null,
        var inputType: InputObjectTypeDefinition? = null,
        var ordering: EnumTypeDefinition? = null,
        var filterType: InputObjectTypeDefinition? = null,
        var query: BaseDataFetcher? = null)

fun createNodeMutation(ctx: Translator.Context, type: NodeFacade, metaProvider: MetaProvider): Augmentation {
    val typeName = type.name()
    val idField = type.fieldDefinitions().find { it.isID() }
    val scalarFields = type.fieldDefinitions().filter { it.type.isScalar() }.sortedByDescending { it == idField }
    if (scalarFields.isEmpty()) {
        return Augmentation()
    }
    val result = Augmentation()
    if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName)) {
        if (type is ObjectDefinitionNodeFacade) {
            if (type.isRelationType()) {
                result.create = CreateRelationTypeHandler.build(type, metaProvider)
            } else {
                result.create = CreateTypeHandler.build(type, metaProvider)
            }
        }
        result.delete = DeleteHandler.build(type, metaProvider)
        result.merge = MergeOrUpdateHandler.build(type, true, metaProvider)
        result.update = MergeOrUpdateHandler.build(type, false, metaProvider)
    }
    if (ctx.query.enabled && !ctx.query.exclude.contains(typeName)) {
        result.inputType = InputObjectTypeDefinition.newInputObjectDefinition()
            .name("_${typeName}Input")
            .inputValueDefinitions(getInputValueDefinitions(scalarFields) { true })
            .build()

        val filterType = filterType(typeName, scalarFields)

        val ordering = EnumTypeDefinition.newEnumTypeDefinition()
            .name("_${typeName}Ordering")
            .enumValueDefinitions(scalarFields.flatMap { fd ->
                listOf("_asc", "_desc")
                    .map { EnumValueDefinition.newEnumValueDefinition().name(fd.name + it).build() }
            })
            .build()

        result.filterType = filterType
        result.ordering = ordering
        result.query = QueryHandler.build(type, filterType.name, ordering.name, metaProvider)
    }
    return result
}

fun createRelationshipMutation(
        ctx: Translator.Context,
        source: ObjectTypeDefinition,
        target: ObjectTypeDefinition,
        metaProvider: MetaProvider,
        relationTypes: Map<String, ObjectTypeDefinition>? = emptyMap()
): Augmentation? {
    val sourceTypeName = source.name
    if (!ctx.mutation.enabled || ctx.mutation.exclude.contains(sourceTypeName)) {
        return null
    }
    val result = Augmentation()
    result.delete = DeleteRelationHandler.build(source, target, metaProvider)
    result.create = CreateRelationHandler.build(source, target, relationTypes, metaProvider)
    return result
}

private fun filterType(name: String?, fieldArgs: List<FieldDefinition>): InputObjectTypeDefinition {

    val fName = "_${name}Filter"
    val builder = InputObjectTypeDefinition.newInputObjectDefinition()
        .name(fName)
    listOf("AND", "OR", "NOT")
        .forEach { builder.inputValueDefinition(input(it, ListType(NonNullType(TypeName(fName))))) }
    // TODO allow also deep filter of relations
    fieldArgs.forEach { field ->
        Operators
            .forType(field.type)
            .forEach { op -> builder.inputValueDefinition(input(op.fieldName(field.name), field.type.inner())) }
    }
    return builder.build()
}



