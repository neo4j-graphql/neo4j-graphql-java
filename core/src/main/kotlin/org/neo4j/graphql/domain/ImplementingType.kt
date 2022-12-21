package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.atteo.evo.inflector.English
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.utils.CamelCaseUtils.camelCase

sealed class ImplementingType(
    val name: String,
    val description: Description? = null,
    val comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    val otherDirectives: List<Directive>,
    val interfaces: List<Interface>,
    val exclude: ExcludeDirective? = null,
    val auth: AuthDirective? = null,
) : FieldContainer<BaseField>(fields) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Interface) return false

        if (name != other.name) return false

        return true
    }

    fun isOperationAllowed(op: ExcludeDirective.ExcludeOperation) = exclude?.operations?.contains(op) != true

    override fun hashCode(): Int {
        return name.hashCode()
    }

    open val singular: String by lazy { leadingUnderscores(name) + camelCase(name) }

    open val plural: String by lazy { leadingUnderscores(name) + English.plural(camelCase(name)) }

    val pascalCaseSingular: String by lazy { this.singular.capitalize() }

    val pascalCasePlural: String by lazy { this.plural.capitalize() }

    protected fun leadingUnderscores(name: String): String {
        return Regex("^(_+).+").matchEntire(name)?.groupValues?.get(1) ?: "";
    }
}
