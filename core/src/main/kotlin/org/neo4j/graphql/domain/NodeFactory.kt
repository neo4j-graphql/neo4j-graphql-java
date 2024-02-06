package org.neo4j.graphql.domain

import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.directives.FulltextDirective
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.isList
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
        jwtShape: Node?,
        schema: Schema,
    ): Node {

        val schemeDirectives =
            typeDefinitionRegistry.schemaExtensionDefinitions?.map { it.directives }?.flatten() ?: emptyList()
        val annotations = Annotations(schemeDirectives + definition.directives, jwtShape)
        val interfaces = definition.implements.mapNotNull { interfaceFactory(it.name()) }
        val fields = FieldFactory.createFields(
            definition,
            typeDefinitionRegistry,
            relationshipPropertiesFactory,
            interfaceFactory,
            schemaConfig
        )
        annotations.fulltext?.validate(definition, fields)
        annotations.limit?.validate(definition.name)

        val node = Node(
            definition.name,
            definition.description,
            definition.comments,
            fields,
            interfaces,
            annotations,
            schema,
        )
        node.annotations.authorization?.initWhere(node)
        fields.forEach { it.annotations.authorization?.initWhere(node) }
        return node
    }

    private fun FulltextDirective.validate(
        definition: ObjectTypeDefinition,
        fields: List<BaseField>
    ): FulltextDirective {
        this.indexes.groupBy { it.indexName }.forEach { (name, indices) ->
            if (indices.size > 1) {
                throw IllegalArgumentException("Node '${definition.name}' @fulltext index contains duplicate name '$name'")
            }
        }

        val stringFieldNames = fields.asSequence()
            .filterIsInstance<PrimitiveField>()
            .filterNot { it.typeMeta.type.isList() }
            .filter { it.typeMeta.type.name() == Constants.Types.String.name }
            .map { it.fieldName }
            .toSet()

        this.indexes.forEach { index ->
            index.fields.forEach { fieldName ->
                if (!stringFieldNames.contains(fieldName)) {
                    throw IllegalArgumentException("Node '${definition.name}' @fulltext index contains invalid index '${index.indexName}' cannot use find String field '${fieldName}'")
                }
            }
        }
        return this
    }
}
