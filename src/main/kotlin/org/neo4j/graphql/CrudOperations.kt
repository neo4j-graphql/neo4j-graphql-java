package org.neo4j.graphql

import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME

data class Augmentation(val create: String = "", val merge: String = "", val update: String = "", val delete: String = "",
        val inputType: String = "", val ordering: String = "", val filterType: String = "", val query: String = "")

fun createNodeMutation(ctx: Translator.Context, type: NodeDefinitionFacade): Augmentation {
    if (type.isRealtionType()) {
        return Augmentation()
    }
    val typeName = type.name()
    val idField = type.fieldDefinitions().find { it.type.name() == "ID" }
    val scalarFields = type.fieldDefinitions().filter { it.type.isScalar() }.sortedByDescending { it == idField }
    val hasIdField = type.fieldDefinitions().find { it.name == "_id" } != null
    val idFieldArg = idField?.let { it.name + ":" + it.type.render() }

    val result = if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName) && scalarFields.isNotEmpty()) {
        val fieldArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render() }

        var create = ""
        var delete = ""
        var merge = ""
        var update = ""
        if (type is ObjectDefinitionNodeFacade) {
            create = """create$typeName($fieldArgs) : $typeName """
        }
        if (idField != null) {
            delete = """delete$typeName($idFieldArg) : $typeName """
            merge = """merge$typeName($fieldArgs) : $typeName """
            update = """update$typeName($fieldArgs) : $typeName """
        }
        Augmentation(create = create, delete = delete, merge = merge, update = update)
    } else Augmentation()

    return if (ctx.query.enabled && !ctx.query.exclude.contains(typeName) && scalarFields.isNotEmpty()) {
        val fieldArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render(false) }
        result.copy(inputType = """input _${typeName}Input { $fieldArgs } """,
                ordering = """enum _${typeName}Ordering { ${scalarFields.joinToString(",") { it.name + "_asc ," + it.name + "_desc" }} } """,
                filterType = filterType(typeName, scalarFields), // TODO
                query = """${typeName.decapitalize()}($fieldArgs, ${if (hasIdField) "" else "_id: Int, "}filter:_${typeName}Filter, orderBy:_${typeName}Ordering, first:Int, offset:Int) : [$typeName!] """)
    } else result
}


fun createRelationshipMutation(
        ctx: Translator.Context,
        source: ObjectTypeDefinition,
        target: ObjectTypeDefinition,
        relationTypes: Map<String, ObjectTypeDefinition>? = emptyMap()
): Augmentation? {
    val sourceTypeName = source.name
    return if (!ctx.mutation.enabled || ctx.mutation.exclude.contains(sourceTypeName)) {
        null
    } else {
        val targetField = source.getFieldByType(target.name) ?: return null
        val sourceIdField = source.fieldDefinitions.find { it.isID() }
        val targetIdField = target.fieldDefinitions.find { it.isID() }
        if (sourceIdField == null || targetIdField == null) {
            return null
        }
        val targetFieldName = targetField.name.capitalize()
        val targetIDStr = if (targetField.isList()) "[ID!]!" else "ID!"
        val relationType = targetField
            .getDirective(RELATION)
            ?.getArgument(RELATION_NAME)
            ?.value?.toJavaValue()?.toString()
            .let { relationTypes?.get(it) }
        val fieldArgs = if (relationType != null) {
            val scalarFields = relationType.fieldDefinitions.filter { it.type.isScalar() && !it.isID() }
            scalarFields.joinToString(", ", ", ") { it.name + ":" + it.type.render() }
        } else ""
        Augmentation(
                create = "add$sourceTypeName$targetFieldName(${sourceIdField.name}:ID!, ${targetField.name}:$targetIDStr$fieldArgs) : $sourceTypeName",
                delete = "delete$sourceTypeName$targetFieldName(${sourceIdField.name}:ID!, ${targetField.name}:$targetIDStr) : $sourceTypeName")
    }
}

fun createRelationshipTypeMutation(
        ctx: Translator.Context,
        type: ObjectTypeDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry
): Augmentation? {

    if (!type.isRealtionType()) {
        return null
    }
    val typeName = type.name
    val idField = type.fieldDefinitions.find { it.isNativeId() }
    val scalarFields = type.fieldDefinitions.filter { it.type.isScalar() }.sortedByDescending { it == idField }

    val result = if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName)) {

        val relation = type.relationship { directiveName, arg ->
            typeDefinitionRegistry.getDirectiveDefinition(directiveName).get().inputValueDefinitions
                .find { it.name == arg }?.defaultValue?.toJavaValue()?.toString()
        }!!

        var create = ""
        var delete = ""
        var merge = ""
        var update = ""

        val typeResolver: (String?) -> NodeFacade? = { name -> typeDefinitionRegistry.getNodeType(name) }
        val startIdFields = relation.getStartFieldIds(typeResolver)
        val endIdFields = relation.getEndFieldIds(typeResolver)
        if (startIdFields.isNotEmpty() && endIdFields.isNotEmpty()) {
            val createArgs =
                    ((startIdFields + endIdFields).map { it.argumentName + ":" + it.field.type.render() } +
                            scalarFields.filter { !it.isNativeId() }.map { it.name + ":" + it.type.render() })
                        .joinToString(", ")
            create = """create$typeName($createArgs) : $typeName """
        }

        if (idField != null) {
            val idFieldArg = idField.let { it.name + ":" + it.type.render() }

            delete = """delete$typeName($idFieldArg) : $typeName """

            if (scalarFields.filter { !it.isNativeId() }.isNotEmpty()) {
                val updateArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render() }
                merge = """merge$typeName($updateArgs) : $typeName """
                update = """update$typeName($updateArgs) : $typeName """
            }
        }
        Augmentation(create, merge, update, delete)
    } else {
        Augmentation()
    }

    return if (ctx.query.enabled && !ctx.query.exclude.contains(typeName) && scalarFields.isNotEmpty()) {
        val fieldArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render(false) }
        result.copy(inputType = """input _${typeName}Input { $fieldArgs } """,
                ordering = """enum _${typeName}Ordering { ${scalarFields.joinToString(",") { it.name + "_asc ," + it.name + "_desc" }} } """,
                filterType = filterType(typeName, scalarFields), // TODO
                query = """${typeName.decapitalize()}($fieldArgs, filter:_${typeName}Filter, orderBy:_${typeName}Ordering, first:Int, offset:Int) : [$typeName!] """)
    } else result
}

private fun filterType(name: String?, fieldArgs: List<FieldDefinition>): String {
    val fName = """_${name}Filter"""
    val fields = (listOf("AND", "OR", "NOT").map { "$it:[$fName!]" } +
            fieldArgs.flatMap { field -> Operators.forType(field.type).map { op -> op.fieldName(field.name) + ":" + field.type.render(false) } }).joinToString(", ")
    return """input $fName { $fields } """
}


