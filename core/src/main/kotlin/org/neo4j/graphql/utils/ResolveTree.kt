package org.neo4j.graphql.utils

import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingFieldSelectionSet
import graphql.schema.SelectedField
import org.neo4j.graphql.aliasOrName
import org.neo4j.graphql.schema.model.inputs.Dict

open class ObjectFieldSelection<SELECTION, ARGS>(
    resolveTree: IResolveTree,
    val parsedSelection: SELECTION,
    val parsedArguments: ARGS
) : IResolveTree by resolveTree

interface IResolveTree {
    val name: String
    val alias: String?
    val args: Dict
    val fieldsByTypeName: Map<String, Map<String, ResolveTree>>

    val aliasOrName get() = alias ?: name

    fun getFieldOfType(typeName: String, fieldName: String) =
        this.fieldsByTypeName[typeName]?.values?.filter { it.name == fieldName } ?: emptyList()

    fun <SEL> getFieldOfType(
        typeName: String,
        fieldName: String,
        selectionConverter: (rt: IResolveTree) -> SEL
    ) = getFieldOfType(typeName, fieldName, selectionConverter, { null })

    fun <SEL, ARGS> getFieldOfType(
        typeName: String,
        fieldName: String,
        selectionConverter: (rt: IResolveTree) -> SEL,
        argsConverter: (rt: IResolveTree) -> ARGS
    ) = getFieldOfType(typeName, fieldName)
        .map { it.parse(selectionConverter, argsConverter) }

    fun getSingleFieldOfType(typeName: String, fieldName: String) =
        getFieldOfType(typeName, fieldName)
            .also { if (it.size > 1) error("expect only one selection for $name::$fieldName") }
            .singleOrNull()

    fun <SEL> getSingleFieldOfType(
        typeName: String,
        fieldName: String,
        selectionConverter: (rt: IResolveTree) -> SEL
    ) = getSingleFieldOfType(typeName, fieldName)
        ?.let { it.parse(selectionConverter, { null }) }


    fun <SEL, ARGS> parse(
        selectionConverter: (rt: IResolveTree) -> SEL,
        argsConverter: (rt: IResolveTree) -> ARGS
    ): ObjectFieldSelection<SEL, ARGS> {
        val selection = selectionConverter(this)
        val args = argsConverter(this)
        return ObjectFieldSelection(this, selection, args)
    }
}

class SelectionOfType(
    val treePerAlias: Map<String /* Alias or Name*/, ResolveTree>
) : Map<String /* Alias or Name*/, ResolveTree> by treePerAlias {

    fun getAliasOfField(name: String) = treePerAlias
        .filter { it.value.name == name }
        .firstNotNullOfOrNull { it.key }
}

class ResolveTree(
    override val name: String,
    override val alias: String? = null,
    override val args: Dict = Dict.EMPTY,
    override val fieldsByTypeName: Map<String /*TypeName*/, SelectionOfType> = emptyMap(),
) : IResolveTree {


    companion object {

        fun resolve(env: DataFetchingEnvironment): ResolveTree {
            return ResolveTree(env.field.name, env.field.alias, Dict(env.arguments), resolve(env.selectionSet))
        }

        fun resolve(field: SelectedField): ResolveTree {
            return ResolveTree(field.name, field.alias, Dict(field.arguments), resolve(field.selectionSet))
        }

        fun resolve(selectionSet: DataFetchingFieldSelectionSet): Map<String, SelectionOfType> {
            val fieldsByTypeName = mutableMapOf<String, MutableMap<String, ResolveTree>>()
            selectionSet.immediateFields.map { selectedField ->
                val field = resolve(selectedField)
                // TODO do we need the interfaces here as well?
//                selectedField.objectTypes
//                    .flatMap { objectType -> (objectType.interfaces.map { it.name } + objectType.name) }
                selectedField.objectTypeNames
                    .map { fieldsByTypeName.computeIfAbsent(it) { mutableMapOf() } }
                    .forEach { it[selectedField.aliasOrName()] = field }
            }
            return fieldsByTypeName
                .mapValues { SelectionOfType(it.value) }
                .takeIf { it.isNotEmpty() }
                ?: emptyMap()
        }

        /**
         * Generates missing fields based on an array of fieldNames
         */
        fun generateMissingOrAliasedFields(
            fieldNames: Set<String>,
            selection: Map<*, ResolveTree>
        ): Map<String, ResolveTree> {

            //TODO harmonize with ResolveTree::extend
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

    fun extend(type: String, additionalFields: List<String>): ResolveTree {
        return additionalFields
            .filter { getFieldOfType(type, it).isEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { extraFields ->
                val newSubSelection = fieldsByTypeName.toMutableMap()
                val resolveTreeOfType = newSubSelection[type]?.toMutableMap() ?: mutableMapOf()
                extraFields.forEach {
                    resolveTreeOfType[it] = ResolveTree(it)
                }
                newSubSelection[type] = SelectionOfType(resolveTreeOfType)
                ResolveTree(name, alias, args, newSubSelection)
            }
            ?: this

    }

}
