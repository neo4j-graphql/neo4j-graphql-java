package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.ObjectValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.get
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

class FullTextDirective(
    val indexes: List<FullTextIndex>
) {

    class FullTextIndex(value: ObjectValue) {
        val indexName: String = value.get(FullTextIndex::indexName) ?: throw IllegalArgumentException("indexName is required")
        val fields: List<String> = value.get(FullTextIndex::fields) ?: emptyList()
        val defaultThreshold: Long? = value.get(FullTextIndex::defaultThreshold)
    }

    companion object {
        fun create(directive: Directive): FullTextDirective {
            directive.validateName(DirectiveConstants.FULLTEXT)
            return FullTextDirective(directive.readRequiredArgument(FullTextDirective::indexes) { value ->
                (value as ArrayValue).values.map { FullTextIndex(it as ObjectValue) }
            })
        }
    }
}
