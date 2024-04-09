package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

class DeclareRelationshipDirective(
    nestedOperations: Set<RelationshipNestedOperations>,
    aggregate: Boolean,
) : RelationshipBaseDirective(nestedOperations, aggregate) {


    companion object {
        const val NAME = "declareRelationship"

        fun create(directive: Directive): DeclareRelationshipDirective {
            directive.validateName(NAME)

            val nestedOperations = extractNestedOperations(directive)
            val aggregate = directive.readArgument(DeclareRelationshipDirective::aggregate) ?: true

            return DeclareRelationshipDirective(nestedOperations, aggregate)
        }
    }
}


