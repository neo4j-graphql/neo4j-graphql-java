package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.CommonAnnotations
import org.neo4j.graphql.utils.CamelCaseUtils

sealed class BaseNames<A : CommonAnnotations>(
    val name: String,
    val annotations: A
) {
    protected val singular = leadingUnderscores(name) + CamelCaseUtils.camelCase(name)

    open val whereInputTypeName get() = "${name}Where"

    companion object {
        protected fun leadingUnderscores(name: String): String {
            return Regex("^(_+).+").matchEntire(name)?.groupValues?.get(1) ?: "";
        }
    }
}
