package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.RELATIONSHIP_REQUIREMENT_PREFIX
import org.neo4j.graphql.domain.Node


//TODO complete
object RelationshipValidationTranslator {

    fun createRelationshipValidations(
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        context: QueryContext?,
        schemaConfig: SchemaConfig,
    ): List<Statement> = node.relationFields.mapNotNull { field ->
        if (field.typeMeta.type.isList()) {
            return@mapNotNull null
        }

        val toNode = field.node ?:  return@mapNotNull null

        val c = Cypher.name("c")
        val (predicate, errorMessage) = if (field.typeMeta.type.isRequired()) {
            c.eq(1.asCypherLiteral()) to "${RELATIONSHIP_REQUIREMENT_PREFIX}${node.name}.${field.fieldName} required"
        } else {
            c.lte(1.asCypherLiteral()) to "${RELATIONSHIP_REQUIREMENT_PREFIX}${node.name}.${field.fieldName} required"
        }
        val relVarName = schemaConfig.namingStrategy.resolveName(
            varName.requiredSymbolicName.value,
            field.fieldName,
            toNode.name,
            "unique"
        )
        val dslRelation = field.createDslRelation(varName, toNode.asCypherNode(context), relVarName)

        Cypher.with(varName)
            .match(dslRelation)
            .with(Functions.count(dslRelation.requiredSymbolicName).`as`(c))
            .apocValidate(predicate, errorMessage)
            .returning(c.`as`(schemaConfig.namingStrategy.resolveName(relVarName, "ignored")))
            .build()
    }
}
