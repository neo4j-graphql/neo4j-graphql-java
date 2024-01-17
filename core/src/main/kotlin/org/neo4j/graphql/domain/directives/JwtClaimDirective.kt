package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class JwtClaimDirective(
    val path: String,
) {

    companion object {
        const val NAME = "jwtClaim"

        fun create(directive: Directive): JwtClaimDirective {
            directive.validateName(NAME)
            return JwtClaimDirective(
                directive.readRequiredArgument(JwtClaimDirective::path)
            )
        }
    }
}


