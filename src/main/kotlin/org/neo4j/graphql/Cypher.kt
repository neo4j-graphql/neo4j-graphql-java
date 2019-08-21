package org.neo4j.graphql

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType

data class Cypher(val query: String, val params: Map<String, Any?> = emptyMap()) {
    fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
    fun escapedQuery() = query.replace("\"", "\\\"").replace("'", "\\'")

    companion object {
        val EMPTY = Cypher("")

        private fun findRelNodeId(objectType: GraphQLObjectType) = objectType.fieldDefinitions.find { it.isID() }!!

        private fun createRelStatement(source: GraphQLType, target: GraphQLFieldDefinition,
                keyword: String = "MERGE"): String {
            val innerTarget = target.type.inner()
            val relationshipDirective = target.getDirective("relation")
                    ?: throw IllegalArgumentException("Missing @relation directive for relation ${target.name}")
            val targetFilterType = if (target.type.isList()) "IN" else "="
            val sourceId = findRelNodeId(source as GraphQLObjectType)
            val targetId = findRelNodeId(innerTarget as GraphQLObjectType)
            val (left, right) = if (relationshipDirective.getRelationshipDirection() == "OUT") ("" to ">") else ("<" to "")
            return "MATCH (from:${source.name.quote()} {${sourceId.name.quote()}:$${sourceId.name}}) " +
                    "MATCH (to:${innerTarget.name.quote()}) WHERE to.${targetId.name.quote()} $targetFilterType $${target.name} " +
                    "$keyword (from)$left-[r:${relationshipDirective.getRelationshipType().quote()}]-$right(to) "
        }

        fun createRelationship(source: GraphQLType, target: GraphQLFieldDefinition): Cypher {
            return Cypher(createRelStatement(source, target))
        }

        fun deleteRelationship(source: GraphQLType, target: GraphQLFieldDefinition): Cypher {
            return Cypher(createRelStatement(source, target, "MATCH") +
                    "DELETE r ")
        }
    }
}