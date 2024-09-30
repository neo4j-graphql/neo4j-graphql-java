package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.model.inputs.WhereInput
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
        where: WhereInput?,
        additionalPredicates: Condition? = null,
    ): OngoingReading {

        val (match, conditions) = Cypher.match(cypherNode) to Cypher.noCondition()

        var whereCondition = WhereResult(conditions)
        whereCondition = whereCondition and createWhere(node, where, cypherNode, schemaConfig, queryContext)
        whereCondition = whereCondition and additionalPredicates

        return if (whereCondition.preComputedSubQueries.isNotEmpty()) {
            (match.withSubQueries(whereCondition.preComputedSubQueries) as ExposesWith)
                .with(Cypher.asterisk())
                .where(whereCondition.requiredCondition)
        } else {
            match.where(whereCondition.requiredCondition)
        }
    }
}
