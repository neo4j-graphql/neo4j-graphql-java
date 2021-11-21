package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.filter.OptimizedFilterHandler

/**
 * This class handles all the logic related to the deletion of nodes.
 */
class BatchDeleteHandler private constructor(schemaConfig: SchemaConfig, typeName: String) : BaseDataFetcherForContainerBatch(schemaConfig, typeName), ReturnsStatistics {
    companion object {
        const val DELETE_INFO = "DeleteInfo"
    }

    private var isRelation: Boolean = false

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        companion object {
            const val METHOD_NAME_PREFIX = "delete"
        }

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val filter = addFilterType(type)
            val plural = English.plural(type.name).capitalize()

            val result = if (schemaConfig.enableStatistics) {
                NonNullType(TypeName(DELETE_INFO))
            } else {
                TypeName(DELETE_INFO)
            }
            val deleteField = FieldDefinition.newFieldDefinition()
                .name(METHOD_NAME_PREFIX + plural)
                .inputValueDefinitions(listOf(
                        input(if (schemaConfig.useWhereFilter) WHERE else FILTER, NonNullType(TypeName(filter)))
                ))
                .type(result)
                .build()


            addMutationField(deleteField)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) return null
            if (fieldDefinition.cypherDirective() != null) return null

            val type = typeDefinitionRegistry
                .getTypes(ImplementingTypeDefinition::class.java)
                .firstOrNull { fieldDefinition.name == METHOD_NAME_PREFIX + English.plural(it.name) }
            if (type == null || !canHandle(type)) {
                return null
            }
            return BatchDeleteHandler(schemaConfig, type.name)
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName) || isRootType(type)) {
                return false
            }
            return type.getIdField() != null || schemaConfig.queryOptionStyle == SchemaConfig.InputStyle.INPUT_TYPE
        }
    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType, graphQLSchema: GraphQLSchema) {
        super.initDataFetcher(fieldDefinition, parentType, graphQLSchema)
        isRelation = type.isRelationType()
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val fieldDefinition = env.fieldDefinition
        val (propertyContainer, match) = when {
            isRelation -> org.neo4j.cypherdsl.core.Cypher.anyNode().relationshipTo(org.neo4j.cypherdsl.core.Cypher.anyNode(), type.label()).named("this")
                .let { rel -> rel to org.neo4j.cypherdsl.core.Cypher.match(rel) }
            else -> org.neo4j.cypherdsl.core.Cypher.node(type.label()).named("this")
                .let { node -> node to org.neo4j.cypherdsl.core.Cypher.match(node) }
        }

        val ongoingReading = if ((env.getContext() as? QueryContext)?.optimizedQuery?.contains(QueryContext.OptimizationStrategy.FILTER_AS_MATCH) == true) {

            OptimizedFilterHandler(type, schemaConfig).generateFilterQuery(variable, fieldDefinition, env.arguments, match, propertyContainer, env.variables)

        } else {

            val where = where(propertyContainer, fieldDefinition, type, env.arguments, env.variables)
            match.where(where)
        }

        return ongoingReading
            .detachDelete(propertyContainer.requiredSymbolicName)
            .build()
    }
}
