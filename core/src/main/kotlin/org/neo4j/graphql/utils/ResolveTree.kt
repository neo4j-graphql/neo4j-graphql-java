package org.neo4j.graphql.utils

import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingFieldSelectionSet
import graphql.schema.SelectedField
import org.neo4j.graphql.aliasOrName
import org.neo4j.graphql.schema.model.inputs.Dict

interface IResolveTree {
    val name: String
    val alias: String?
    val args: Dict
    val fieldsByTypeName: Map<String, Map<String, IResolveTree>>

    val aliasOrName get() = alias ?: name

    fun getFieldOfType(typeName: String, fieldName: String) =
        this.fieldsByTypeName[typeName]?.values?.filter { it.name == fieldName } ?: emptyList()
}

class SelectionOfType(
    private val treePerAlias: Map<String /* Alias or Name*/, ResolveTree>
) : Map<String /* Alias or Name*/, ResolveTree> by treePerAlias {

    fun getAliasOfField(name: String) = treePerAlias
        .filter { it.value.name == name }
        .firstNotNullOfOrNull { it.key }

    fun getByFieldName(name: String) = treePerAlias.values
        .firstOrNull { it.name == name }

    fun forEachField(name: String, callback: (ResolveTree) -> Unit) {
        values.filter { it.name == name }.forEach(callback)
    }

    fun merge(selectionOfType: SelectionOfType): SelectionOfType {
        val newTreePerAlias = treePerAlias.toMutableMap()
        selectionOfType.forEach { (name, tree) ->
            newTreePerAlias.compute(name) { _, existingTree ->
                existingTree?.extend(tree) ?: tree
            }

        }
        return SelectionOfType(newTreePerAlias)
    }
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

        private fun resolve(field: SelectedField): ResolveTree {
            return ResolveTree(field.name, field.alias, Dict(field.arguments), resolve(field.selectionSet))
        }

        private fun resolve(selectionSet: DataFetchingFieldSelectionSet): Map<String, SelectionOfType> {
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
    }

    override fun toString(): String {
        return "ResolveTree(name='$name', alias=$alias, args=$args, fieldsByTypeName=$fieldsByTypeName)"
    }

    fun extend(block: ResolveTreeBuilder.() -> Unit): ResolveTree {
        val builder = ResolveTreeBuilder(this)
        block(builder)
        return builder.buildResolveTree()
    }

    fun extend(other: ResolveTree): ResolveTree {
        val newFieldsByTypeName = fieldsByTypeName.toMutableMap()
        other.fieldsByTypeName.forEach { (typeName, selectionOfType) ->
            newFieldsByTypeName.compute(typeName) { _, existingSelectionOfType ->
                existingSelectionOfType?.merge(selectionOfType) ?: selectionOfType
            }
        }
        return ResolveTree(name, alias, args, newFieldsByTypeName)
    }
}
