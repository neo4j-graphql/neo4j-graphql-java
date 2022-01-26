package org.neo4j.graphql.parser

import graphql.language.*
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.RelationField

typealias CypherDSL = org.neo4j.cypherdsl.core.Cypher

/**
 * An internal representation of all the filtering passed to a graphql field
 */
class ParsedQuery(
    val fieldPredicates: List<FieldPredicate>,
    val relationPredicates: List<RelationPredicate>,
    val or: List<*>? = null,
    val and: List<*>? = null
) {

    fun getFieldConditions(
        propertyContainer: PropertyContainer,
        variablePrefix: String,
        variableSuffix: String,
        schemaConfig: SchemaConfig
    ): Condition =
        fieldPredicates
            .flatMap { it.createCondition(propertyContainer, variablePrefix, variableSuffix, schemaConfig) }
            .reduceOrNull { result, condition -> result.and(condition) }
            ?: Conditions.noCondition()
}

abstract class Predicate<T>(
    val op: T,
    val value: Any?,
    val normalizedName: String,
    val index: Int
)

/**
 * Predicates on a nodes' or relations' property
 */
class FieldPredicate(
    op: FieldOperator,
    value: Any?,
    val field: BaseField,
    index: Int
) : Predicate<FieldOperator>(op, value, normalizeName(field.fieldName, op.suffix.toCamelCase()), index) {

    fun createCondition(
        propertyContainer: PropertyContainer,
        variablePrefix: String,
        variableSuffix: String,
        schemaConfig: SchemaConfig
    ) =
        op.resolveCondition(
            variablePrefix,
            normalizedName,
            propertyContainer,
            field,
            value,
            schemaConfig,
            variableSuffix
        )

}

/**
 * Predicate for a relation
 */

class RelationPredicate(
    op: RelationOperator,
    value: Any?,
    val field: RelationField,
    index: Int
) : Predicate<RelationOperator>(op, value, normalizeName(field.fieldName, op.suffix.toCamelCase()), index) {

    val targetFieldContainer: FieldContainer<*>? get() = this.field.node ?: this.field.interfaze
    val targetName: String? get() = this.field.node?.name ?: this.field.interfaze?.name

    val relationshipInfo: RelationshipInfo<GraphQLFieldsContainer>
        get() {
//        type.relationshipFor(field.fieldName)!!
            TODO("remove")
        }

    val fieldDefinition: GraphQLFieldDefinition
        get() {
            TODO("remove")
        }

    val relNode: Node = field.node?.asCypherNode() ?: TODO()

    fun createRelation(start: Node, end: Node): Relationship {
        return when (field.direction) {
            RelationField.Direction.IN -> start.relationshipFrom(end, field.relationType)
            RelationField.Direction.OUT -> start.relationshipTo(end, field.relationType)
        }
    }

    fun createExistsCondition(propertyContainer: PropertyContainer): Condition {
        val relation = createRelation(
            propertyContainer as? Node
                ?: throw IllegalStateException("""propertyContainer is expected to be a Node but was ${propertyContainer.javaClass.name}"""),
            relNode
        )
        val condition = CypherDSL.match(relation).asCondition()
        return when (op) {
            RelationOperator.NOT -> condition
            RelationOperator.EQ_OR_NOT_EXISTS -> condition.not()
            else -> throw IllegalStateException("$op should not be set for Null value")
        }

    }
}

object QueryParser {

    fun parseFilter(filter: Map<*, *>, type: GraphQLFieldsContainer): ParsedQuery {
//        // Map of all queried fields
//        // we remove all matching fields from this map, so we can ensure that only known fields got queried
//        val queriedFields = filter
//            .entries
//            .mapIndexed { index, (key, value) -> key as String to (index to value) }
//            .toMap(mutableMapOf())
//        val or = queriedFields.remove("OR")?.second?.let {
//            (it as? List<*>)
//                    ?: throw IllegalArgumentException("OR on type `${type.name}` is expected to be a list")
//        }
//        val and = queriedFields.remove("AND")?.second?.let {
//            (it as? List<*>)
//                    ?: throw IllegalArgumentException("AND on type `${type.name}` is expected to be a list")
//        }
//        return createParsedQuery(queriedFields, type, or, and)
        TODO("remove")
    }

    fun parseFilter(
        filter: Map<*, *>,
        typeName: String,
        fields: FieldContainer<*>,
        schemaConfig: SchemaConfig,
    ): ParsedQuery {
        // Map of all queried fields
        // we remove all matching fields from this map, so we can ensure that only known fields got queried
        val queriedFields = filter
            .entries
            .mapIndexed { index, (key, value) -> key as String to (index to value) }
            .toMap(mutableMapOf())
        val or = queriedFields.remove("OR")?.second?.let {
            (it as? List<*>) ?: throw IllegalArgumentException("OR on type `${typeName}` is expected to be a list")
        }
        val and = queriedFields.remove("AND")?.second?.let {
            (it as? List<*>)
                ?: throw IllegalArgumentException("AND on type `${typeName}` is expected to be a list")
        }
        return createParsedQuery(queriedFields, typeName, fields, or, and, schemaConfig)
    }

    /**
     * This parser takes all non-filter arguments of a graphql-field and transform it to the internal [ParsedQuery]-representation
     */
    fun parseArguments(
        arguments: Map<String, Any>,
        fieldDefinition: GraphQLFieldDefinition,
        type: GraphQLFieldsContainer
    ): ParsedQuery {
//        // Map of all queried fields
//        // we remove all matching fields from this map, so we can ensure that only known fields got queried
//        val queriedFields = arguments
//            .entries
//            .mapIndexed { index, (key, value) -> key to (index to value as Any?) }
//            .toMap(mutableMapOf())
//        var index = queriedFields.size
//        fieldDefinition.arguments
//            .filter { it.argumentDefaultValue.value != null }
//            .filterNot { queriedFields.containsKey(it.name) }
//            .filterNot { ProjectionBase.SPECIAL_FIELDS.contains(it.name) }
//            .forEach { argument ->
//                queriedFields[argument.name] = index++ to argument.argumentDefaultValue.value
//            }
//
//        return createParsedQuery(queriedFields, type)
        TODO("remove")
    }

    private fun createParsedQuery(
        queriedFields: MutableMap<String, Pair<Int, Any?>>,
        typeName: String,
        fields: FieldContainer<*>,
        or: List<*>? = null,
        and: List<*>? = null,
        schemaConfig: SchemaConfig,
    ): ParsedQuery {
        // find all matching fields
        val fieldPredicates = mutableListOf<FieldPredicate>()
        val relationPredicates = mutableListOf<RelationPredicate>()
        for (definedField in fields.fields) {
            if (definedField is RelationField) {
                RelationOperator.values()
                    .map { it to it.fieldName(definedField.fieldName, schemaConfig) }
                    .mapNotNull { (queryOp, queryFieldName) ->
                        queriedFields.remove(queryFieldName)?.let { (index, filter) ->
                            val harmonizedOperator = queryOp.harmonize(typeName, definedField, filter, queryFieldName)
                            RelationPredicate(harmonizedOperator, filter, definedField, index)
                        }
                    }
                    .forEach { relationPredicates.add(it) }
            } else {
                FieldOperator.values()
                    .map { it to it.fieldName(definedField.fieldName, schemaConfig) }
                    .mapNotNull { (predicate, queryFieldName) ->
                        queriedFields[queryFieldName]?.let { (index, objectField) ->
                            if (predicate.requireParam xor (objectField != null)) {
                                // if we got a value but the predicate requires none,
                                // or we got a no value but the predicate requires one
                                // we skip this operator
                                null
                            } else {
                                queriedFields.remove(queryFieldName)
                                FieldPredicate(predicate, objectField, definedField, index)
                            }
                        }
                    }
                    .forEach { fieldPredicates.add(it) }
            }
        }

        if (queriedFields.isNotEmpty()) {
            throw IllegalArgumentException("queried unknown fields ${queriedFields.keys} on type ${typeName}")
        }

        return ParsedQuery(
            fieldPredicates.sortedBy(Predicate<*>::index),
            relationPredicates.sortedBy(Predicate<*>::index),
            or, and
        )
    }
}



