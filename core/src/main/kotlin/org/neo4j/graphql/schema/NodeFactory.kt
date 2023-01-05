package org.neo4j.graphql.schema

import graphql.language.Directive
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.*
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField

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
    ): Node {

        val otherDirectives = mutableListOf<Directive>()
        var auth: AuthDirective? = null
        var exclude: ExcludeDirective? = null
        var nodeDirective: NodeDirective? = null
        var fulltext: FullTextDirective? = null
        var pluralDirective: PluralDirective? = null
        var queryOptions: QueryOptionsDirective? = null

        definition.directives.forEach {
            when (it.name) {
                DirectiveConstants.AUTH -> auth = AuthDirective.create(it)
                DirectiveConstants.EXCLUDE -> exclude = ExcludeDirective.create(it)
                DirectiveConstants.NODE -> nodeDirective = NodeDirective.create(it)
                DirectiveConstants.FULLTEXT -> fulltext = FullTextDirective.create(it)
                DirectiveConstants.PLURAL -> pluralDirective = PluralDirective.create(it)
                DirectiveConstants.QUERY_OPTIONS -> queryOptions = QueryOptionsDirective.create(it)
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

        val fields = FieldFactory.createFields(
            definition,
            typeDefinitionRegistry,
            relationshipPropertiesFactory,
            interfaceFactory,
            schemaConfig
        )

        return Node(
            definition.name,
            definition.description,
            definition.comments,
            fields,
            otherDirectives,
            interfaces,
            exclude,
            nodeDirective,
            fulltext?.validate(definition, fields),
            queryOptions?.validate(definition.name),
            pluralDirective?.value,
            auth
        )
    }

    private fun FullTextDirective.validate(
        definition: ObjectTypeDefinition,
        fields: List<BaseField>
    ): FullTextDirective {
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
