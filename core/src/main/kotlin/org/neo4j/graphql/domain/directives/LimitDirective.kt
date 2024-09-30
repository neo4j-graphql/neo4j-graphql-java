package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.toJavaValue
import kotlin.Annotation

class LimitDirective private constructor(
    val default: Int?,
    val max: Int?,
) : Annotation {

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
        internal fun create(directive: Directive): LimitDirective {
            return LimitDirective(
                directive.readArgument(LimitDirective::default, { (it.toJavaValue() as? Number)?.toInt() }),
                directive.readArgument(LimitDirective::max, { (it.toJavaValue() as? Number)?.toInt() }),
            )
        }
    }
}


