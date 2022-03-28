package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.ImplementingTypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.aliasOrName
import org.neo4j.graphql.withSubQueries

/**
 * This class handles all the logic related to the deletion of relations starting from an existing node.
 * This includes the augmentation of the delete&lt;Edge&gt;-mutator and the related cypher generation
 */
class DeleteRelationHandler private constructor(schemaConfig: SchemaConfig) : BaseRelationHandler("delete", schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : BaseRelationFactory("delete", schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {

            if (!canHandleType(type)) {
                return
            }
            type.fieldDefinitions
                .filter { canHandleField(it) }
                .mapNotNull { targetField ->
                    buildFieldDefinition(type, targetField, true)
                        ?.let { builder -> addMutationField(builder.build()) }
                }
        }

        override fun createDataFetcher(): DataFetcher<Cypher> = DeleteRelationHandler(schemaConfig)

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val arguments = field.arguments.associateBy { it.name }
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)
        val relName = org.neo4j.cypherdsl.core.Cypher.name("r")

        val withAlias = startNode.`as`(variable)
        val (mapProjection, subQueries) = projectFields(startNode, type, env, withAlias.asName())

        return org.neo4j.cypherdsl.core.Cypher
            .match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .match(relation.createRelation(startNode, endNode).named(relName))
            .delete(relName).withDistinct(withAlias)
            .withSubQueries(subQueries)
            .returning(withAlias.asName().project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }

}
