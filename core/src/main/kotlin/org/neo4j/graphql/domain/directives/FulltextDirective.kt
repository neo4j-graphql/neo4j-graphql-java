package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.ObjectValue
import org.neo4j.graphql.get
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

class FulltextDirective(
    val indexes: List<FullTextIndex>
) {

    class FullTextIndex(value: ObjectValue) {
        val indexName: String =
            value.get(FullTextIndex::indexName) ?: throw IllegalArgumentException("indexName is required")
        val queryName: String? = value.get(FullTextIndex::queryName)
        val fields: List<String> = value.get(FullTextIndex::fields) ?: emptyList()
    }

    companion object {
        const val NAME = "fulltext"
        fun create(directive: Directive): FulltextDirective {
            directive.validateName(NAME)
            return FulltextDirective(directive.readRequiredArgument(FulltextDirective::indexes) { value ->
                (value as ArrayValue).values.map { FullTextIndex(it as ObjectValue) }
            })
        }
    }
}
