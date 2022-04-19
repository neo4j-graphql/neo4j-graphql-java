package org.neo4j.graphql.utils

import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingFieldSelectionSet
import graphql.schema.SelectedField

class ResolveTree(
    val name: String,
    val alias: String? = null,
    val args: Map<String, *> = emptyMap<String, Any>(),
    val fieldsByTypeName: Map<String, Map<*, ResolveTree>> = emptyMap(),
) {
    companion object {

        fun resolve(env: DataFetchingEnvironment): ResolveTree {
            return ResolveTree(env.field.name, env.field.alias, env.arguments, resolve(env.selectionSet))
        }

        fun resolve(field: SelectedField): ResolveTree {
            return ResolveTree(field.name, field.alias, field.arguments, resolve(field.selectionSet))
        }

        fun resolve(selectionSet: DataFetchingFieldSelectionSet): Map<String, Map<*, ResolveTree>> {
            val fieldsByTypeName = mutableMapOf<String, MutableMap<String, ResolveTree>>()
            selectionSet.immediateFields.map { selectedField ->
                val field = resolve(selectedField)
                // TODO do we need the interfaces here as well?
//                selectedField.objectTypes
//                    .flatMap { objectType -> (objectType.interfaces.map { it.name } + objectType.name) }
                selectedField.objectTypeNames
                    .map { fieldsByTypeName.computeIfAbsent(it) { mutableMapOf() } }
                    .forEach { it[selectedField.alias ?: selectedField.name] = field }
            }
            return fieldsByTypeName.takeIf { it.isNotEmpty() } ?: emptyMap()
        }

        /**
         * Generates missing fields based on an array of fieldNames
         */
        fun generateMissingOrAliasedFields(
            fieldNames: Set<String>,
            selection: Map<*, ResolveTree>
        ): Map<String, ResolveTree> {
            val result = mutableMapOf<String, ResolveTree>()
            fieldNames.forEach { fieldName ->
                val exists = getResolveTreeByFieldName(fieldName, selection)
                val aliased = getAliasedResolveTreeByFieldName(fieldName, selection)
                if (exists == null || aliased != null) {
                    result[fieldName] = ResolveTree(name = fieldName)
                }
            }
            return result
        }

        /**
         * Finds a resolve tree of selection based on field name
         */
        fun getResolveTreeByFieldName(fieldName: String, selection: Map<*, ResolveTree>) =
            selection.values.find { it.name == fieldName }

        /**
         * Finds an aliased resolve tree of selection based on field name
         */
        fun getAliasedResolveTreeByFieldName(fieldName: String, selection: Map<*, ResolveTree>) =
            selection.values.find { it.name == fieldName && it.alias != null && it.alias != fieldName }
    }

    override fun toString(): String {
        return "ResolveTree(name='$name', alias=$alias, args=$args, fieldsByTypeName=$fieldsByTypeName)"
    }

    fun getFieldOfType(typeName: String, fieldName: String): ResolveTree? =
        this.fieldsByTypeName[typeName]?.values?.find { it.name == fieldName }


}
