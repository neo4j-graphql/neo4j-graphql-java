package org.neo4j.graphql.domain.fields

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import graphql.language.InputValueDefinition
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.UniqueDirective
import org.neo4j.graphql.isRequired

/**
 * Representation a ObjectTypeDefinitionNode field.
 */
sealed class BaseField(
    var fieldName: String,
    var typeMeta: TypeMeta,
) {
    var otherDirectives: List<Directive> = emptyList()
    val deprecatedDirective
        get() = otherDirectives.firstOrNull { it.name == "deprecated" }

    var arguments: List<InputValueDefinition> = emptyList()
    var private: Boolean = false
    var auth: AuthDirective? = null
    var description: Description? = null
    var comments: List<Comment> = emptyList()
    var readonly: Boolean = false
    var writeonly: Boolean = false
    var ignored: Boolean = false
    var dbPropertyName: String = fieldName
    var unique: UniqueDirective? = null
    lateinit var owner: FieldContainer<*>

    override fun toString(): String {
        return "Field: $fieldName"
    }

    open val generated: Boolean get() = false

    val required: Boolean get() = typeMeta.type.isRequired()

    fun getOwnerName() = owner.let {
        when (it) {
            is Node -> it.name
            is Interface -> it.name
            is RelationshipProperties -> it.interfaceName
            else -> throw IllegalStateException("cannot determine name from owner of type ${it::class.java.name}")
        }
    }

    val interfaceField get() = (owner as? Node)
        ?.interfaces
        ?.mapNotNull { it.getField(fieldName) }
        ?.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseField

        if (fieldName != other.fieldName) return false

        return true
    }

    override fun hashCode(): Int {
        return fieldName.hashCode()
    }
}
