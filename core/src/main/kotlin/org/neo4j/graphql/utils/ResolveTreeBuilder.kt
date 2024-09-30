package org.neo4j.graphql.utils

class ResolveTreeBuilder(
    private val newFieldsByTypeName: MutableMap<String /*TypeName*/, SelectionOfType> = mutableMapOf(),
    private val source: ResolveTree? = null
) {
    private var changed: Boolean = false

    constructor(source: ResolveTree) : this(source.fieldsByTypeName.toMutableMap(), source)

    fun select(
        fieldName: String,
        typeName: String,
        alias: String? = null,
        block: (ResolveTreeBuilder.() -> Unit)? = null
    ): ResolveTreeBuilder {
        val selectionOfType = newFieldsByTypeName[typeName]
        val existingField = selectionOfType?.getByFieldName(fieldName)

        val newFieldBuilder = ResolveTreeBuilder(existingField ?: ResolveTree(fieldName, alias ?: fieldName))
        block?.invoke(newFieldBuilder)
        val newFieldTree = newFieldBuilder.buildResolveTree()
        changed = existingField == null || changed || newFieldBuilder.changed
        if (changed) {
            newFieldsByTypeName[typeName] = SelectionOfType(
                (selectionOfType ?: emptyMap()) + mapOf(fieldName to newFieldTree)
            )
        }
        return this
    }

    fun buildResolveTree(): ResolveTree {
        requireNotNull(source) { "source must not be null" }
        return if (changed) {
            ResolveTree(source.name, source.alias, source.args, newFieldsByTypeName)
        } else {
            source
        }
    }

    fun getFieldsByTypeName(): Map<String, SelectionOfType> {
        return newFieldsByTypeName
    }
}
