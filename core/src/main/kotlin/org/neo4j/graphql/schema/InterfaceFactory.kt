package org.neo4j.graphql.schema

import graphql.language.Directive
import graphql.language.InterfaceTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.name

/**
 * A factory to create the internal representation of a [Node]
 */
object InterfaceFactory {

    fun createInterface(
        definition: InterfaceTypeDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        relationshipPropertiesFactory: (name: String) -> RelationshipProperties?,
        interfaceFactory: (name: String) -> Interface?,
    ): Interface {

        val otherDirectives = mutableListOf<Directive>()
        var auth: AuthDirective? = null
        var exclude: ExcludeDirective? = null

        definition.directives.forEach {
            when (it.name) {
                DirectiveConstants.AUTH -> auth = AuthDirective.create(it)
                DirectiveConstants.EXCLUDE -> exclude = ExcludeDirective.create(it)
                else -> otherDirectives += it
            }
        }

        val interfaces = definition.implements.mapNotNull { interfaceFactory(it.name()) }

        return Interface(
            definition.name,
            definition.description,
            definition.comments,
            FieldFactory.creteFields(
                definition,
                typeDefinitionRegistry,
                relationshipPropertiesFactory,
                interfaceFactory
            ),
            otherDirectives,
            interfaces,
            exclude,
            auth,
        )
    }
}
