package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.graphql.asCypherLiteral

enum class RelationOperator(
    val suffix: String?,
    val list: Boolean,
    private val wrapInNotIfNeeded: (where: Condition) -> Condition = { it },
) {
    ALL("ALL", list = true),
    NONE("NONE", list = true, wrapInNotIfNeeded = { it.not() }),
    SINGLE("SINGLE", list = true),
    SOME("SOME", list = true),
    NOT_EQUAL("NOT", list = false, wrapInNotIfNeeded = { it.not() }),
    EQUAL(null, list = false);

    fun createRelationCondition(
        relationship: Relationship,
        nestedCondition: Condition?
    ): Condition {
        val inner = nestedCondition ?: Cypher.noCondition()
        val match = Cypher.match(relationship)
        val condition = when (this) {
            ALL ->
                match.let {
                    it.where(inner).asCondition()
                        // Testing "ALL" requires testing that at least one element exists and that no elements not matching the filter exists
                        .and(it.where(inner.not()).asCondition().not())
                }

            SINGLE ->
                Cypher.single(Cypher.name("ignore"))
                    .`in`(Cypher.listBasedOn(relationship).where(inner).returning(1.asCypherLiteral()))
                    .where(Cypher.literalTrue().asCondition())

            else -> match.where(inner).asCondition()
        }
        return wrapInNotIfNeeded(condition)
    }

}
