package org.neo4j.graphql

import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition

data class Augmentation(val create: String = "", val merge: String = "", val update: String = "", val delete: String = "",
        val inputType: String = "", val ordering: String = "", val filterType: String = "", val query: String = "")

fun createNodeMutation(ctx: SchemaConfig, type: ObjectTypeDefinition): Augmentation {
    val typeName = type.name
    val idField = type.fieldDefinitions.find { it.type.name() == "ID" }
    val scalarFields = type.fieldDefinitions.filter { it.type.isScalar() }.sortedByDescending { it == idField }
    val hasIdField = type.fieldDefinitions.find { it.name == "_id" } != null
    val idFieldArg = idField?.let { it.name + ":" + it.type.render() }

    val result = if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName) && scalarFields.isNotEmpty()) {
        val fieldArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render() }
        Augmentation().copy(create = """create$typeName($fieldArgs) : $typeName """)
            .let { aug ->
                if (idField != null) aug.copy(
                        delete = """delete$typeName($idFieldArg) : $typeName """,
                        merge = """merge$typeName($fieldArgs) : $typeName """,
                        update = """update$typeName($fieldArgs) : $typeName """)
                else aug
            }
    } else Augmentation()

    return if (ctx.query.enabled && !ctx.query.exclude.contains(typeName) && scalarFields.isNotEmpty()) {
        val fieldArgs = scalarFields.joinToString(", ") { it.name + ":" + it.type.render(false) }
        result.copy(inputType = """input _${typeName}Input { $fieldArgs } """,
                ordering = """enum _${typeName}Ordering { ${scalarFields.joinToString(",") { it.name + "_asc ," + it.name + "_desc" }} } """,
                filterType = filterType(typeName, scalarFields), // TODO
                query = """${typeName.decapitalize()}($fieldArgs, ${if (hasIdField) "" else "_id: Int, "}filter:_${typeName}Filter, orderBy:_${typeName}Ordering, first:Int, offset:Int) : [$typeName] """)
    } else result
}

fun createRelationshipMutation(ctx: SchemaConfig, source: ObjectTypeDefinition, target: ObjectTypeDefinition): Augmentation? {
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
        Augmentation(
                create = "add$sourceTypeName$targetFieldName(${sourceIdField.name}:ID!, ${targetField.name}:$targetIDStr) : $sourceTypeName",
                delete = "delete$sourceTypeName$targetFieldName(${sourceIdField.name}:ID!, ${targetField.name}:$targetIDStr) : $sourceTypeName")
    }
}

private fun filterType(name: String?, fieldArgs: List<FieldDefinition>): String {
    val fName = """_${name}Filter"""
    val fields = (listOf("AND", "OR", "NOT").map { "$it:[$fName!]" } +
            fieldArgs.flatMap { field -> Operators.forType(field.type).map { op -> op.fieldName(field.name) + ":" + field.type.render(false) } }).joinToString(", ")
    return """input $fName { $fields } """
}


