package org.neo4j.graphql.domain

import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.Annotations
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
        schemaConfig: SchemaConfig,
    ): Node? {

        val schemeDirectives =
            typeDefinitionRegistry.schemaExtensionDefinitions?.map { it.directives }?.flatten() ?: emptyList()
        val annotations = Annotations(schemeDirectives + definition.directives, typeDefinitionRegistry, definition.name)
        if (annotations.relationshipProperties != null) {
            return null
        }
        val interfaces = definition.implements.mapNotNull { interfaceFactory(it.name()) }
        val fields = FieldFactory.createFields(
            definition,
            typeDefinitionRegistry,
            relationshipPropertiesFactory,
            schemaConfig
        )
        annotations.limit?.validate(definition.name)

        val node = Node(
            definition.name,
            definition.description,
            definition.comments,
            fields,
            interfaces,
            annotations,
        )
        return node
    }
}
