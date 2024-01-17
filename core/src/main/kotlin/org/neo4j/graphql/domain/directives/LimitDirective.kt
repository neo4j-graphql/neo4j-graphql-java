package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class LimitDirective(
    val default: Int?,
    val max: Int?,
) {

    fun validate(typeName: String) {
        when {
            default != null && default <= 0 ->
                throw IllegalArgumentException("$typeName @queryOptions(limit: {default: ${default}}) invalid value: '${default}', it should be a number greater than 0")

            max != null && max <= 0 ->
                throw IllegalArgumentException("$typeName @queryOptions(limit: {max: ${max}}) invalid value: '${max}', it should be a number greater than 0")

            max != null && default != null ->
                require(max >= default) { "$typeName @queryOptions(limit: {max: ${max}, default: ${default}}) invalid default value, 'default' must be smaller than 'max'" }
        }
    }

    companion object {
        const val NAME = "limit"
        fun create(directive: Directive): LimitDirective {
            directive.validateName(NAME)
            return LimitDirective(
                directive.readArgument(LimitDirective::default),
                directive.readArgument(LimitDirective::max),
            )
        }
    }
}


