package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.fields.CypherField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.isList
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.utils.ResolveTree
import org.neo4j.graphql.withSubQueries

fun projectCypherField(
    selection: ResolveTree,
    field: CypherField,
    propertyContainer: PropertyContainer,
    resultReference: SymbolicName,
    queryContext: QueryContext,
    schemaConfig: SchemaConfig,
): Statement {

    val referenceNode = field.node
    val referenceUnion = field.union

    val isArray = field.typeMeta.type.isList()
    val expectMultipleValues = (referenceNode != null || referenceUnion != null) && isArray;

    val statementResultVariable = Cypher.name(ChainString(schemaConfig, propertyContainer, field).resolveName())

    val customCypherQuery = Cypher
        .with(propertyContainer)
        .with(
            selection.args.map {
                queryContext.getNextParam(it.value).`as`(it.key)
            } + propertyContainer.requiredSymbolicName.`as`("this"))
        .returningRaw(
            Cypher.raw(field.statement)
                .let {
                    when (field.resultAlias) {
                        null -> it.`as`(statementResultVariable)
                        else -> it
                    }
                }
        ).build()


    var ongoingReading: OngoingReading = Cypher
        .with(propertyContainer)
        .call(customCypherQuery)
        .let {
            when (field.resultAlias) {
                null -> it
                else -> it.with(Cypher.name(field.resultAlias).`as`(statementResultVariable))
            }
        }

    val projection = when {
        referenceNode != null -> {

            val endNode = Cypher.anyNode(statementResultVariable)

            val nestedProjection = ProjectionTranslator()
                .createProjectionAndParams(
                    referenceNode,
                    endNode,
                    selection,
                    chainStr = null,
                    schemaConfig,
                    queryContext
                )
            ongoingReading = ongoingReading
                .withSubQueries(nestedProjection.allSubQueries)

            statementResultVariable.project(nestedProjection.projection)

        }

        referenceUnion != null -> {

            val unionConditions = referenceUnion.nodes.values
                .map { Conditions.hasLabelsOrType(statementResultVariable, *it.allLabels(queryContext).toTypedArray()) }
                .fold(Conditions.noCondition(), Condition::or)

            ongoingReading = ongoingReading
                .with(Cypher.asterisk())
                .where(unionConditions)

            var case = Cypher.caseExpression()

            referenceUnion.nodes.values.forEachIndexed { index, refNode ->

                val nodeSelection = selection.fieldsByTypeName[refNode.name]
                if (nodeSelection != null) {
                    val endNode = Cypher.anyNode(ChainString(schemaConfig, resultReference, index).resolveName())
                    val nestedProjection = ProjectionTranslator()
                        .createProjectionAndParams(
                            refNode,
                            endNode,
                            selection,
                            chainStr = null,
                            schemaConfig,
                            queryContext,
                            resolveType = true
                        )
                    if (nestedProjection.allSubQueries.isNotEmpty()) {
                        ongoingReading = ongoingReading
                            .with(listOf(Cypher.asterisk(), statementResultVariable.`as`(endNode.requiredSymbolicName)))
                            .withSubQueries(nestedProjection.allSubQueries)
                    }

                    val labelsPredicate =
                        Conditions.hasLabelsOrType(
                            statementResultVariable,
                            *refNode.allLabels(queryContext).toTypedArray()
                        )

                    case = case
                        .`when`(labelsPredicate)
                        .then(statementResultVariable.project(nestedProjection.projection))
                } else {
                    TODO("empty projection")
                }
            }
            case
        }

        else -> {
            statementResultVariable
        }
    }

    val finalProjection = Functions.collect(projection).let {
        when {
            expectMultipleValues -> it
            else -> Functions.head(it)
        }
    }


    return ongoingReading
        .returning(finalProjection.`as`(resultReference))
        .build()
}
