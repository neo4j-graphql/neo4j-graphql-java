package org.neo4j.graphql.domain

import graphql.language.InterfaceTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.Annotations
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
        schemaConfig: SchemaConfig,
    ): Interface {

        val annotations = Annotations(definition.directives, typeDefinitionRegistry, definition.name)
        val interfaces = definition.implements.mapNotNull { interfaceFactory(it.name()) }

        return Interface(
            definition.name,
            definition.description,
            definition.comments,
            FieldFactory.createFields(
                definition,
                typeDefinitionRegistry,
                relationshipPropertiesFactory,
                schemaConfig
            ),
            interfaces,
            annotations,
        )
    }
}
