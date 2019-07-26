package org.neo4j.graphql

import graphql.language.*
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.handler.*
import org.neo4j.graphql.handler.projection.ProjectionHandler
import org.neo4j.graphql.handler.projection.ProjectionRepository
import org.neo4j.graphql.handler.relation.CreateRelationHandler
import org.neo4j.graphql.handler.relation.CreateRelationTypeHandler
import org.neo4j.graphql.handler.relation.DeleteRelationHandler

data class Augmentation(
        var create: BaseDataFetcher? = null,
        var merge: BaseDataFetcher? = null,
        var update: BaseDataFetcher? = null,
        var delete: BaseDataFetcher? = null,
        var inputType: InputObjectTypeDefinition? = null,
        var ordering: EnumTypeDefinition? = null,
        var filterType: InputObjectTypeDefinition? = null,
        var query: BaseDataFetcher? = null)

fun createNodeMutation(
        ctx: Translator.Context,
        type: NodeFacade,
        metaProvider: MetaProvider,
        projectionRepository: ProjectionRepository)
        : Augmentation {
    val typeName = type.name()
    val idField = type.fieldDefinitions().find { it.isID() }
    val scalarFields = type.fieldDefinitions().filter { it.type.isScalar() }.sortedByDescending { it == idField }
    if (scalarFields.isEmpty()) {
        return Augmentation()
    }

    val isRelation = type.isRelationType()

    val result = Augmentation()
    if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName)) {
        if (type is ObjectDefinitionNodeFacade) {
            if (isRelation) {
                val relation = type.relationship(metaProvider)!!
                val startIdField = relation.getStartFieldId(metaProvider)
                val endIdField = relation.getEndFieldId(metaProvider)
                if (startIdField != null && endIdField != null) {
                    val createArgs = scalarFields
                        .filter { !it.isNativeId() }
                        .filter { it.name != startIdField.argumentName }
                        .filter { it.name != endIdField.argumentName }

                    val builder = createFieldDefinition("create", typeName, emptyList())
                        .inputValueDefinition(input(startIdField.argumentName, startIdField.field.type))
                        .inputValueDefinition(input(endIdField.argumentName, endIdField.field.type))
                    createArgs.forEach { builder.inputValueDefinition(input(it.name, it.type)) }

                    result.create = CreateRelationTypeHandler(type,
                            relation,
                            startIdField,
                            endIdField,
                            builder.build(),
                            metaProvider,
                            projectionRepository)

                }
            } else {
                result.create = CreateTypeHandler(type,
                        createFieldDefinition("create", typeName, scalarFields.filter { !it.isNativeId() }).build(),
                        metaProvider,
                        projectionRepository)
            }
        }
        if (idField != null) {

            result.delete = DeleteHandler(type,
                    idField,
                    createFieldDefinition("delete", typeName, listOf(idField))
                        .description(Description("Deletes $typeName and returns its ID on successful deletion", null, false))
                        .type(idField.type.inner())
                        .build(),
                    metaProvider,
                    projectionRepository)

            if (scalarFields.any { !it.isID() }) {
                result.merge = MergeOrUpdateHandler(type,
                        true,
                        idField,
                        createFieldDefinition("merge", typeName, scalarFields).build(),
                        metaProvider,
                        projectionRepository)

                result.update = MergeOrUpdateHandler(type,
                        false,
                        idField,
                        createFieldDefinition("update", typeName, scalarFields).build(),
                        metaProvider,
                        projectionRepository)
            }
        }
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
        result.query = QueryHandler(type,
                FieldDefinition
                    .newFieldDefinition()
                    .name(typeName.decapitalize())
                    .inputValueDefinitions(getInputValueDefinitions(scalarFields) { true })
                    .inputValueDefinition(input("filter", TypeName(filterType.name)))
                    .inputValueDefinition(input("orderBy", TypeName(ordering.name)))
                    .inputValueDefinition(input("first", TypeName("Int")))
                    .inputValueDefinition(input("offset", TypeName("Int")))
                    .type(NonNullType(ListType(NonNullType(TypeName(typeName)))))
                    .build(),
                metaProvider,
                projectionRepository)
        projectionRepository.add(type.name(), ProjectionHandler(metaProvider))
    }
    return result
}

fun createRelationshipMutation(
        ctx: Translator.Context,
        source: ObjectTypeDefinition,
        target: ObjectTypeDefinition,
        metaProvider: MetaProvider,
        projectionRepository: ProjectionRepository,
        relationTypes: Map<String, ObjectTypeDefinition>? = emptyMap()
): Augmentation? {
    val sourceTypeName = source.name
    if (!ctx.mutation.enabled || ctx.mutation.exclude.contains(sourceTypeName)) {
        return null
    }
    val targetField = source.getFieldByType(target.name) ?: return null

    val sourceIdField = source.fieldDefinitions.find { it.isID() }
    val targetIdField = target.fieldDefinitions.find { it.isID() }
    if (sourceIdField == null || targetIdField == null) {
        return null
    }

    val targetFieldName = targetField.name.capitalize()
    val idType = NonNullType(TypeName("ID"))
    val targetIDType = if (targetField.isList()) NonNullType(ListType(idType)) else idType

    val relationType = targetField
        .getDirective(RELATION)
        ?.getArgument(RELATION_NAME)
        ?.value?.toJavaValue()?.toString()
        .let { relationTypes?.get(it) }

    val sourceNodeType = source.getNodeType()!!
    val targetNodeType = target.getNodeType()!!
    val relation = sourceNodeType.relationshipFor(targetField.name, metaProvider)
            ?: throw IllegalArgumentException("could not resolve relation for " + source.name + "::" + targetField.name)

    val result = Augmentation()
    result.delete = DeleteRelationHandler(sourceNodeType, // TODO pass directly to method
            relation,
            RelationshipInfo.RelatedField(sourceIdField.name, sourceIdField, sourceNodeType),
            RelationshipInfo.RelatedField(targetField.name, targetIdField, targetNodeType),
            FieldDefinition.newFieldDefinition()
                .name("delete$sourceTypeName$targetFieldName")
                .inputValueDefinition(input(sourceIdField.name, idType))
                .inputValueDefinition(input(targetField.name, targetIDType))
                .type(NonNullType(TypeName(sourceTypeName)))
                .build(),
            metaProvider,
            projectionRepository)


    val type = FieldDefinition.newFieldDefinition()
        .name("add$sourceTypeName$targetFieldName")
        .inputValueDefinition(input(sourceIdField.name, idType))
        .inputValueDefinition(input(targetField.name, targetIDType))
        .type(NonNullType(TypeName(sourceTypeName)))

    relationType
        ?.fieldDefinitions
        ?.filter { it.type.isScalar() && !it.isID() }
        ?.forEach { type.inputValueDefinition(input(it.name, it.type)) }

    result.create = CreateRelationHandler(sourceNodeType, // TODO pass directly to method
            relation,
            RelationshipInfo.RelatedField(sourceIdField.name, sourceIdField, sourceNodeType),
            RelationshipInfo.RelatedField(targetField.name, targetIdField, targetNodeType),
            type.build(),
            metaProvider,
            projectionRepository)
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

private fun input(name: String, type: Type<*>): InputValueDefinition {
    return InputValueDefinition.newInputValueDefinition().name(name).type(type).build()
}

private fun createFieldDefinition(
        prefix: String,
        typeName: String,
        scalarFields: List<FieldDefinition>,
        forceOptionalProvider: (field: FieldDefinition) -> Boolean = { false }
): FieldDefinition.Builder {
    return FieldDefinition.newFieldDefinition()
        .name("$prefix$typeName")
        .inputValueDefinitions(getInputValueDefinitions(scalarFields, forceOptionalProvider))
        .type(NonNullType(TypeName(typeName)))
}

private fun getInputValueDefinitions(scalarFields: List<FieldDefinition>, forceOptionalProvider: (field: FieldDefinition) -> Boolean): List<InputValueDefinition> {
    return scalarFields
        .map { input(it.name, if (forceOptionalProvider.invoke(it)) it.type.optional() else it.type) }
}



