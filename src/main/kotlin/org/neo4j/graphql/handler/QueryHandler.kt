package org.neo4j.graphql.handler

import graphql.language.*
import org.neo4j.graphql.*

class QueryHandler private constructor(
        type: NodeFacade,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    private val isList = fieldDefinition.type.isList()

    companion object {
        fun build(type: NodeFacade, filterTypeName: String, orderingTypename: String, metaProvider: MetaProvider): BaseDataFetcher? {
            val typeName = type.name()
            val scalarFields = type.scalarFields()

            val fieldDefinition = FieldDefinition
                .newFieldDefinition()
                .name(typeName.decapitalize())
                .inputValueDefinitions(getInputValueDefinitions(scalarFields) { true })
                // TODO use constants
                .inputValueDefinition(input("filter", TypeName(filterTypeName)))
                .inputValueDefinition(input("orderBy", TypeName(orderingTypename)))
                .inputValueDefinition(input("first", TypeName("Int")))
                .inputValueDefinition(input("offset", TypeName("Int")))
                .type(NonNullType(ListType(NonNullType(TypeName(typeName)))))
                .build()
            return QueryHandler(type, fieldDefinition, metaProvider)
        }

        fun build(fieldDefinition: FieldDefinition,
                isQuery: Boolean,
                metaProvider: MetaProvider,
                type: NodeFacade = metaProvider.getNodeType(fieldDefinition.type.name())
                        ?: throw IllegalStateException("cannot find type " + fieldDefinition.type.name())
        ): BaseDataFetcher? {
            val cypherDirective = fieldDefinition.cypherDirective()
            return when {
                cypherDirective != null -> CypherDirectiveHandler(type, isQuery, cypherDirective, fieldDefinition, metaProvider)
                isQuery -> QueryHandler(type, fieldDefinition, metaProvider)
                else -> null
            }
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {

        val mapProjection = projectionProvider.invoke()
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments).format()

        val select = if (type.isRelationType()) {
            "()-[$variable:${label()}]->()"
        } else {
            "($variable:${label()})"
        }
        val where = where(variable, fieldDefinition, type, propertyArguments(field), ctx)
        return Translator.Cypher("MATCH $select${where.query}" +
                " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                (where.params + mapProjection.params + skipLimit.params),
                isList)
    }
}