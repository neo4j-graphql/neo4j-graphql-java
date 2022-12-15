package org.neo4j.graphql.domain.fields

import graphql.language.*
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.UniqueDirective
import org.neo4j.graphql.isRequired

/**
 * Representation a ObjectTypeDefinitionNode field.
 */
abstract class BaseField(
    var fieldName: String,
    var typeMeta: TypeMeta,
) {
    var otherDirectives: List<Directive> = emptyList()
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
    lateinit var owner: Any

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
