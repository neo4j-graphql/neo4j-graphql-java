package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthorizationDirective
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.filter.FulltextPerIndex
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.withSubQueries

class TopLevelMatchTranslator(
    val schemaConfig: SchemaConfig,
    val variables: Map<String, Any>,
    val queryContext: QueryContext
) {

    fun translateTopLevelMatch(
        node: Node,
        cypherNode: org.neo4j.cypherdsl.core.Node,
        fulltextInput: FulltextPerIndex?,
        where: WhereInput?,
        authOperation: AuthorizationDirective.AuthorizationOperation,
        additionalPredicates: Condition? = null
    ): OngoingReading {

        val varName = ChainString(schemaConfig, cypherNode)

        val (match, conditions) = if (fulltextInput != null) {
            createFulltextSearchMatch(fulltextInput, node, varName)
        } else {
            Cypher.match(cypherNode) to Cypher.noCondition()
        }

        var whereCondition = WhereResult(conditions)
        whereCondition = whereCondition and createWhere(node, where, cypherNode, varName, schemaConfig, queryContext)
        whereCondition = whereCondition and additionalPredicates
        whereCondition = whereCondition and AuthorizationFactory.getAuthConditions(
            node,
            cypherNode,
            null, // TODO fields
            schemaConfig,
            queryContext,
            authOperation
        )

        return if (whereCondition.preComputedSubQueries.isNotEmpty()) {
            (match.withSubQueries(whereCondition.preComputedSubQueries) as ExposesWith)
                .with(Cypher.asterisk())
                .where(whereCondition.requiredCondition)
        } else {
            match.where(whereCondition.requiredCondition)
        }
    }

    private fun createFulltextSearchMatch(
        fulltextInput: FulltextPerIndex,
        node: Node,
        varName: ChainString
    ): Pair<StatementBuilder.OngoingStandaloneCallWithReturnFields, Condition> {
        if (fulltextInput.size > 1) {
            throw IllegalArgumentException("Can only call one search at any given time");
        }
        val (indexName, indexInput) = fulltextInput.entries.first()

        val thisName = Cypher.name("node").`as`("this")
        val scoreName = Cypher.name("score").`as`("score")
        val call = Cypher.call("db.index.fulltext.queryNodes")
            .withArgs(
                indexName.asCypherLiteral(),
                varName.extend("fulltext", indexName, "phrase").resolveParameter(indexInput.phrase)
            )
            .yield(thisName, scoreName)

        var cond = Conditions.noCondition()
        // TODO remove this? https://github.com/neo4j/graphql/issues/1189
        if (node.additionalLabels.isNotEmpty()) {
            // TODO add Functions.labels(#symbolicName) // node.hasLabels()
            node.allLabels(queryContext).forEach {
                cond =
                    cond.and(it.asCypherLiteral().`in`(Cypher.call("labels").withArgs(thisName).asFunction()))
            }
        }

        if (node.annotations.fulltext != null) {
            indexInput.score
                ?.let { varName.extend("fulltext", indexName, "score", "EQUAL").resolveParameter(it) }
                ?.let { cond = cond.and(scoreName.eq(it)) }
        }

        return call to cond
    }
}
