package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.ImplementingTypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of relations starting from an existing node.
 * This includes the augmentation of the add&lt;Edge&gt;-mutator and the related cypher generation
 */
class CreateRelationHandler private constructor(schemaConfig: SchemaConfig) : BaseRelationHandler("add", schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : BaseRelationFactory("add", schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {

            if (!canHandleType(type)) {
                return
            }

            val richRelationTypes = typeDefinitionRegistry.types().values
                .filterIsInstance<ImplementingTypeDefinition<*>>()
                .filter { it.getDirective(DirectiveConstants.RELATION) != null }
                .associate { it.getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME, null)!! to it.name }


            type.fieldDefinitions
                .filter { canHandleField(it) }
                .mapNotNull { targetField ->
                    buildFieldDefinition(type, targetField, nullableResult = false)
                        ?.let { builder ->

                            val relationType = targetField
                                .getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME, null)
                                ?.let { it -> (richRelationTypes[it]) }
                                ?.let { typeDefinitionRegistry.getUnwrappedType(it) as? ImplementingTypeDefinition }

                            relationType
                                ?.fieldDefinitions
                                ?.filterNot { it.isIgnored() }
                                ?.filter { it.type.inner().isScalar() && !it.type.inner().isID() }
                                ?.forEach { builder.inputValueDefinition(input(it.name, it.type)) }

                            addMutationField(builder.build())
                        }

                }
        }

        override fun createDataFetcher(): DataFetcher<Cypher> {
            return CreateRelationHandler(schemaConfig)
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val properties = properties(variable, env.arguments)

        val arguments = field.arguments.associateBy { it.name }
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)

        val withAlias = startNode.`as`(variable)
        val (mapProjection, subQueries) = projectFields(startNode, type, env, withAlias.asName())
        return org.neo4j.cypherdsl.core.Cypher.match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .merge(relation.createRelation(startNode, endNode).withProperties(*properties))
            .withDistinct(withAlias)
            .withSubQueries(subQueries)
            .returning(withAlias.asName().project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
