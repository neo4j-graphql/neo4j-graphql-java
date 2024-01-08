package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.filter.FulltextPerIndex
import org.neo4j.graphql.translate.where.createWhere

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
        authOperation: AuthDirective.AuthOperation,
        additionalPredicates: Condition? = null
    ): OngoingReading {

        val varName = ChainString(schemaConfig, cypherNode)

        var (match, conditions) = if (fulltextInput != null) {
            createFulltextSearchMatch(fulltextInput, node, varName)
        } else {
            Cypher.match(cypherNode) to Conditions.noCondition()
        }

        val (whereConditions, subQueries) = createWhere(node, where, cypherNode, varName, schemaConfig, queryContext)
        whereConditions?.let { conditions = conditions.and(it) }

        if (additionalPredicates != null) {
            conditions = conditions and additionalPredicates
        }

        if (node.auth != null) {
            AuthTranslator(
                schemaConfig,
                queryContext,
                where = AuthTranslator.AuthOptions(cypherNode, node)
            ).createAuth(node.auth, authOperation)
                ?.let { conditions = conditions.and(it) }
        }
        return if (subQueries.isNotEmpty()) {
            (match.withSubQueries(subQueries) as ExposesWith)
                .with(Cypher.asterisk())
                .where(conditions)
        } else {
            match.where(conditions)
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

        if (node.fulltextDirective != null) {
            val index = node.fulltextDirective.indexes.find { it.indexName == indexName }
            (indexInput.score
                ?.let { varName.extend("fulltext", indexName, "score", "EQUAL").resolveParameter(it) }
                ?: index?.defaultThreshold?.let {
                    varName.extend(
                        "fulltext",
                        indexName,
                        "defaultThreshold" // TODO naming: split
                    ).resolveParameter(it)
                })
                ?.let { cond = cond.and(scoreName.eq(it)) }
        }

        return call to cond
    }
}
