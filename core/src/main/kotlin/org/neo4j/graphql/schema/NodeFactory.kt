package org.neo4j.graphql.schema

import graphql.language.Directive
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.directives.FullTextDirective
import org.neo4j.graphql.domain.directives.NodeDirective
import org.neo4j.graphql.name

/**
 * A factory to create the internal representation of a [Node]
 */
object NodeFactory {

    fun createNode(
        definition: ObjectTypeDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        relationshipPropertiesFactory: (name: String) -> RelationshipProperties?,
        interfaceFactory: (name: String) -> Interface?,
    ): Node {

        val otherDirectives = mutableListOf<Directive>()
        var auth: AuthDirective? = null
        var exclude: ExcludeDirective? = null
        var nodeDirective: NodeDirective? = null
        var fulltext: FullTextDirective? = null

        definition.directives.forEach {
            when (it.name) {
                DirectiveConstants.AUTH -> auth = AuthDirective.create(it)
                DirectiveConstants.EXCLUDE -> exclude = ExcludeDirective.create(it)
                DirectiveConstants.NODE -> nodeDirective = NodeDirective.create(it)
                DirectiveConstants.FULLTEXT -> fulltext = FullTextDirective.create(it)
                else -> otherDirectives += it
            }
        }

        val interfaces = definition.implements.mapNotNull { interfaceFactory(it.name()) }


        if (auth == null) {
            val interfaceAuthDirectives = interfaces.mapNotNull { it.auth }
            if (interfaceAuthDirectives.size > 1) {
                throw IllegalArgumentException("Multiple interfaces of ${definition.name} have @auth directive - cannot determine directive to use")
            } else if (interfaceAuthDirectives.isNotEmpty()) {
                auth = interfaceAuthDirectives.first()
            }
        }
        if (exclude == null) {
            val interfaceExcludeDirectives = interfaces.mapNotNull { it.exclude }
            if (interfaceExcludeDirectives.size > 1) {
                throw IllegalArgumentException("Multiple interfaces of ${definition.name} have @exclude directive - cannot determine directive to use")
            } else if (interfaceExcludeDirectives.isNotEmpty()) {
                exclude = interfaceExcludeDirectives.first()
            }
        }

        return Node(
            definition.name,
            definition.description,
            definition.comments,
            FieldFactory.creteFields<Node>(
                definition,
                typeDefinitionRegistry,
                relationshipPropertiesFactory,
                interfaceFactory
            ),
            otherDirectives,
            interfaces,
            exclude,
            nodeDirective,
            // TODO validate
            fulltext,
            auth,
        )
    }
}
