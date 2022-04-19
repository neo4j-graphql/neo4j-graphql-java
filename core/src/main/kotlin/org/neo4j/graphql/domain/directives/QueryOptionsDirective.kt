package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.ObjectValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readField
import org.neo4j.graphql.validateName

data class QueryOptionsDirective(
    val limit: LimitInput?
) {

    data class LimitInput(
        val default: Int?,
        val max: Int?
    ) {
        fun validate(typeName: String) {
            if (default != null && default <= 0) {
                throw IllegalArgumentException("$typeName @queryOptions(limit: {default: ${default}}) invalid value: '${default}', it should be a number greater than 0")
            }
            if (max != null && max <= 0) {
                throw IllegalArgumentException("$typeName @queryOptions(limit: {max: ${max}}) invalid value: '${max}', it should be a number greater than 0")
            }
            if (max != null && default != null) {
                if (max < default) {
                    throw IllegalArgumentException("$typeName @queryOptions(limit: {max: ${max}, default: ${default}}) invalid default value, 'default' must be smaller than 'max'")
                }
            }
        }
    }

    fun validate(typeName: String): QueryOptionsDirective {
        limit?.validate(typeName)
        return this
    }

    companion object {
        fun create(directive: Directive): QueryOptionsDirective {
            directive.validateName(DirectiveConstants.QUERY_OPTIONS)
            val limit = directive.readArgument(QueryOptionsDirective::limit) {
                if (it is ObjectValue) {
                    LimitInput(it.readField(LimitInput::default), it.readField(LimitInput::max))
                } else {
                    null
                }
            }
            return QueryOptionsDirective(limit)
        }
    }
}


