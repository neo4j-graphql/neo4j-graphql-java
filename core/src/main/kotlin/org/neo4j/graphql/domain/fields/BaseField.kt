package org.neo4j.graphql.domain.fields

import graphql.language.Comment
import graphql.language.Description
import graphql.language.InputValueDefinition
import graphql.language.Type
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.isList
import org.neo4j.graphql.isRequired
import org.neo4j.graphql.name

/**
 * Representation a ObjectTypeDefinitionNode field.
 */
sealed class BaseField(
    val fieldName: String,
    val type: Type<*>,
    val annotations: FieldAnnotations
) {
    val deprecatedDirective
        get() = annotations.otherDirectives.firstOrNull { it.name == "deprecated" }
    var arguments: List<InputValueDefinition> = emptyList()

    var description: Description? = null
    var comments: List<Comment> = emptyList()

    //    var ignored: Boolean = false
    open val dbPropertyName get() = annotations.alias?.property ?: fieldName
    open lateinit var owner: FieldContainer<*>

    fun isList() = type.isList()
    fun isRequired() = type.isRequired()
    open val whereType get() = type.name().asType()

    override fun toString(): String {
        return "Field: ${getOwnerName()}::$fieldName"
    }

    fun getOwnerName() = owner.let {
        when (it) {
            is Node -> it.name
            is Interface -> it.name
            is RelationshipProperties -> it.typeName
        }
    }

    val interfaceField
        get() = (owner as? Node)
            ?.interfaces
            ?.mapNotNull { it.getField(fieldName) }
            ?.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseField

        return fieldName == other.fieldName
    }

    override fun hashCode(): Int {
        return fieldName.hashCode()
    }

    fun isCustomResolvable() = this.annotations.customResolver != null

    fun isFilterableByValue() = annotations.filterable?.byValue != false
}
