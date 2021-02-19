package org.neo4j.graphql.parser

import graphql.language.*
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase

typealias CypherDSL = org.neo4j.cypherdsl.core.Cypher

class ParsedQuery(
        val fieldPredicates: List<FieldPredicate>,
        val relationPredicates: List<RelationPredicate>,
        val or: List<Value<*>>? = null,
        val and: List<Value<*>>? = null
) {

    fun getFieldConditions(propertyContainer: PropertyContainer, variablePrefix: String, variableSuffix: String): Condition =
            fieldPredicates
                .flatMap { it.createCondition(propertyContainer, variablePrefix, variableSuffix) }
                .reduceOrNull { result, condition -> result.and(condition) }
                    ?: Conditions.noCondition()
}

abstract class Predicate<T>(
        val op: T,
        val queryField: ObjectField,
        val normalizedName: String,
        val index: Int)

class FieldPredicate(
        op: FieldOperator,
        queryField: ObjectField,
        val fieldDefinition: GraphQLFieldDefinition,
        index: Int
) : Predicate<FieldOperator>(op, queryField, normalizeName(fieldDefinition.name, op.suffix.toCamelCase()), index) {

    fun createCondition(propertyContainer: PropertyContainer, variablePrefix: String, variableSuffix: String) =
            op.resolveCondition(
                    variablePrefix,
                    normalizedName,
                    propertyContainer,
                    fieldDefinition,
                    queryField.value,
                    variableSuffix
            )

}

class RelationPredicate(
        type: GraphQLFieldsContainer,
        op: RelationOperator,
        queryField: ObjectField,
        val fieldDefinition: GraphQLFieldDefinition,
        index: Int
) : Predicate<RelationOperator>(op, queryField, normalizeName(fieldDefinition.name, op.suffix.toCamelCase()), index) {

    val relationshipInfo = type.relationshipFor(fieldDefinition.name)!!
    val relNode: Node = CypherDSL.node((fieldDefinition.type.inner() as? GraphQLObjectType)?.label()!!)

    fun createRelation(start: Node): Relationship = relationshipInfo.createRelation(start, relNode)

    fun createExistsCondition(propertyContainer: PropertyContainer): Condition {
        val relation = createRelation(propertyContainer as? Node
                ?: throw IllegalStateException("""propertyContainer is expected to be a Node but was ${propertyContainer.javaClass.name}"""))
        return when (op) {
            RelationOperator.NOT -> relation.asCondition()
            RelationOperator.EQ_OR_NOT_EXISTS -> Conditions.not(relation)
            else -> throw IllegalStateException("$op should not be set for Null value")
        }

    }
}

object QueryParser {


    fun parseFilter(objectValue: ObjectValue, type: GraphQLFieldsContainer): ParsedQuery {
        // Map of all queried fields
        // we remove all matching fields from this map, so we can ensure that only known fields got queried
        val queriedFields = objectValue.objectFields
            .mapIndexed { index, field -> field.name to (index to field) }
            .toMap(mutableMapOf())
        val or = queriedFields.remove("OR")?.second?.value?.let {
            (it as ArrayValue).values
                    ?: throw IllegalArgumentException("OR on type `${type.name}` is expected to be a list")
        }


        val and = queriedFields.remove("AND")?.second?.value?.let {
            (it as ArrayValue).values
                    ?: throw IllegalArgumentException("AND on type `${type.name}` is expected to be a list")
        }

        return createParsedQuery(queriedFields, type, null, or, and)
    }

    fun parseArguments(arguments: List<Argument>, fieldDefinition: GraphQLFieldDefinition, type: GraphQLFieldsContainer): ParsedQuery {
        // TODO we should check if the argument is defined on the field definition and throw an error otherwise
        // Map of all queried fields
        // we remove all matching fields from this map, so we can ensure that only known fields got queried
        val queriedFields = arguments
            .mapIndexed { index, argument -> argument.name to (index to ObjectField(argument.name, argument.value)) }
            .toMap(mutableMapOf())
        var index = queriedFields.size
        fieldDefinition.arguments
            .filter { it.defaultValue != null }
            .filterNot { queriedFields.containsKey(it.name) }
            .filterNot { setOf(ProjectionBase.FIRST, ProjectionBase.OFFSET, ProjectionBase.ORDER_BY, ProjectionBase.FILTER).contains(it.name) }
            .forEach { argument ->
                queriedFields[argument.name] = index++ to ObjectField(argument.name, argument.defaultValue.asGraphQLValue())
            }

        return createParsedQuery(queriedFields, type, fieldDefinition)
    }


    private fun createParsedQuery(
            queriedFields: MutableMap<String, Pair<Int, ObjectField>>,
            type: GraphQLFieldsContainer,
            fieldDefinition: GraphQLFieldDefinition? = null,
            or: List<Value<Value<*>>>? = null,
            and: List<Value<Value<*>>>? = null
    ): ParsedQuery {
        // find all matching fields
        val fieldPredicates = mutableListOf<FieldPredicate>()
        val relationPredicates = mutableListOf<RelationPredicate>()
        for (definedField in type.fieldDefinitions) {
            if (definedField.isRelationship()) {
                RelationOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (queryOp, queryFieldName) ->
                        queriedFields.remove(queryFieldName)?.let { (index, objectField) ->
                            val harmonizedOperator = queryOp.harmonize(type, definedField, objectField.value, queryFieldName)
                            RelationPredicate(type, harmonizedOperator, objectField, definedField, index)
                        }
                    }
                    .forEach { relationPredicates.add(it) }
            } else {
                FieldOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (predicate, queryFieldName) ->
                        queriedFields[queryFieldName]?.let { (index, objectField) ->
                            if (predicate.requireParam xor (objectField.value !is NullValue)) {
                                // if we got a value but the predicate requires none
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
            throw IllegalArgumentException("queried unknown fields ${queriedFields.keys} on type ${type.name}")
        }

        return ParsedQuery(
                fieldPredicates.sortedBy(Predicate<*>::index),
                relationPredicates.sortedBy(Predicate<*>::index),
                or, and
        )
    }
}



