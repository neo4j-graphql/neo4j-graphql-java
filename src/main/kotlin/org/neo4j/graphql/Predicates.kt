package org.neo4j.graphql

import graphql.Scalars
import graphql.schema.*
import org.neo4j.graphql.Predicate.Companion.resolvePredicate
import org.neo4j.graphql.handler.projection.ProjectionBase

interface Predicate {
    fun toExpression(variable: String): Cypher

    companion object {
        fun resolvePredicate(name: String, value: Any?, type: GraphQLFieldsContainer): Predicate {
            val (fieldName, op) = Operators.resolve(name, value)
            return if (type.hasRelationship(fieldName)) {
                when (value) {
                    is Map<*, *> -> RelationPredicate(fieldName, op, value, type)
                    null -> IsNullPredicate(fieldName, op, type)
                    else -> throw IllegalArgumentException("Input for $fieldName must be an filter-InputType")
                }
            } else {
                ExpressionPredicate(fieldName, op, value, type.getFieldDefinition(fieldName)!!)
            }
        }

        private fun isParam(value: String) = value.startsWith("{") && value.endsWith("}") || value.startsWith("\$")

        @Suppress("unused", "SimplifiableCallChain")
        private fun formatAnyValueCypher(value: Any?): String =
                when (value) {
                    null -> "null"
                    is String -> if (isParam(value)) value else "\"$value\""
                    is Map<*, *> -> "{" + value.map { it.key.toString() + ":" + formatAnyValueCypher(it.value) }.joinToString(",") + "}"
                    is Iterable<*> -> "[" + value.map { formatAnyValueCypher(it) }.joinToString(",") + "]"
                    else -> value.toString()

                }
    }
}

fun toExpression(name: String, value: Any?, type: GraphQLFieldsContainer): Predicate =
        if (name == "AND" || name == "OR")
            when (value) {
                is Iterable<*> -> CompoundPredicate(value.map { toExpression("AND", it, type) }, name)
                is Map<*, *> -> CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type) }, name)
                else -> throw IllegalArgumentException("Unexpected value for filter: $value")
            }
        else {
            resolvePredicate(name, value, type)
        }

data class CompoundPredicate(val parts: List<Predicate>, val op: String = "AND") : Predicate {
    override fun toExpression(variable: String): Cypher =
            parts.map { it.toExpression(variable) }
                .let { expressions ->
                    Cypher(
                            expressions.map { it.query }.joinNonEmpty(" $op ", "(", ")"),
                            expressions.fold(emptyMap()) { res, exp -> res + exp.params }
                    )
                }
}

data class IsNullPredicate(val fieldName: String, val op: Operators, val type: GraphQLFieldsContainer) : Predicate {
    override fun toExpression(variable: String): Cypher {
        val rel = type.relationshipFor(fieldName) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val not = if (op.not) "" else "NOT "
        return Cypher("$not($variable)$left-[:${rel.relType}]-$right()")
    }
}

data class ExpressionPredicate(val name: String, val op: Operators, val value: Any?, val fieldDefinition: GraphQLFieldDefinition) : Predicate {
    val not = if (op.not) "NOT " else ""
    override fun toExpression(variable: String): Cypher {
        val paramName: String = ProjectionBase.FILTER + paramName(variable, name, value).capitalize() + "_" + op.name
        val query = if (fieldDefinition.isNativeId()) {
            if (op.list) {
                "${not}ID($variable) ${op.op} [id IN \$$paramName | toInteger(id)]"
            } else {
                "${not}ID($variable) ${op.op} toInteger(\$$paramName)"
            }
        } else {
            "$not$variable.${name.quote()} ${op.op} \$$paramName"
        }
        return Cypher(query, mapOf(paramName to value))
    }
}


data class RelationPredicate(val fieldName: String, val op: Operators, val value: Map<*, *>, val type: GraphQLFieldsContainer) : Predicate {
    val not = if (op.not) "NOT" else ""
    // (type)-[:TYPE]->(related) | pred] = 0/1/ > 0 | =
    // ALL/ANY/NONE/SINGLE(p in (type)-[:TYPE]->() WHERE pred(last(nodes(p)))
    // ALL/ANY/NONE/SINGLE(x IN [(type)-[:TYPE]->(o) | pred(o)] WHERE x)

    override fun toExpression(variable: String): Cypher {
        val prefix = when (op) {
            Operators.EQ -> "ALL"
            Operators.NEQ -> "ALL" // bc of not
            else -> op.op
        }
        val rel = type.relationshipFor(fieldName) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val other = variable + "_" + rel.typeName
        val cond = other + "_Cond"
        val (pred, params) = CompoundPredicate(value.map { resolvePredicate(it.key.toString(), it.value, rel.type) }).toExpression(other)
        return Cypher("$not $prefix($cond IN [($variable)$left-[:${rel.relType.quote()}]-$right($other) | $pred] WHERE $cond)", params)
    }
}

abstract class UnaryOperator
class IsNullOperator : UnaryOperator()

@Suppress("unused")
enum class Operators(val suffix: String, val op: String, val not: Boolean = false) {
    EQ("", "="),
    IS_NULL("", ""),
    IS_NOT_NULL("not", "", true),
    NEQ("not", "=", true),
    GTE("gte", ">="),
    GT("gt", ">"),
    LTE("lte", "<="),
    LT("lt", "<"),

    NIN("not_in", "IN", true),
    IN("in", "IN"),
    NC("not_contains", "CONTAINS", true),
    NSW("not_starts_with", "STARTS WITH", true),
    NEW("not_ends_with", "ENDS WITH", true),
    C("contains", "CONTAINS"),
    SW("starts_with", "STARTS WITH"),
    EW("ends_with", "ENDS WITH"),

    SOME("some", "ANY"),
    ANY("some", "ANY"),
    NONE("none", "NONE"),
    EVERY("every", "ALL"),
    ALL("every", "ALL"),
    SINGLE("single", "SINGLE")
    ;

    val list = op == "IN"

    companion object {
        private val ops = enumValues<Operators>().sortedWith(Comparator.comparingInt { it.suffix.length }).reversed()
        val allNames = ops.map { it.suffix }
        val allOps = ops.map { it.op }

        fun resolve(field: String, value: Any?): Pair<String, Operators> {
            val fieldOperator = ops.find { field.endsWith("_" + it.suffix) }
            val unaryOperator = if (value is UnaryOperator) unaryOperatorOf(field, value) else EQ
            val op = fieldOperator ?: unaryOperator
            val name = if (op.suffix.isEmpty()) field else field.substring(0, field.length - op.suffix.length - 1)
            return name to op
        }

        private fun unaryOperatorOf(field: String, value: Any?): Operators =
                when (value) {
                    is IsNullOperator -> if (field.endsWith("_not")) IS_NOT_NULL else IS_NULL
                    else -> throw IllegalArgumentException("Unknown unary operator $value")
                }

        fun forType(type: GraphQLInputType): List<Operators> =
                if (type == Scalars.GraphQLBoolean) listOf(EQ, NEQ)
                else if (type is GraphQLEnumType || type is GraphQLObjectType || type is GraphQLTypeReference) listOf(EQ, NEQ, IN, NIN)
                else listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                        if (type == Scalars.GraphQLString || type == Scalars.GraphQLID) listOf(C, NC, SW, NSW, EW, NEW) else emptyList()

        fun forType(type: GraphQLType): List<Operators> =
                when {
                    type == Scalars.GraphQLBoolean -> listOf(EQ, NEQ)
                    type.isNeo4jType() -> listOf(EQ, NEQ, IN, NIN)
                    type is GraphQLFieldsContainer || type is GraphQLInputObjectType -> listOf(IS_NULL, IS_NOT_NULL, SOME, NONE, SINGLE)
                    type is GraphQLEnumType -> listOf(EQ, NEQ, IN, NIN)
                    // todo list types
                    !type.isScalar() -> listOf(EQ, NEQ, IN, NIN)
                    else -> listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                            if (type.name == "String" || type.name == "ID") listOf(C, NC, SW, NSW, EW, NEW) else emptyList()
                }

    }

    fun fieldName(fieldName: String) = if (this.suffix.isBlank()) fieldName else fieldName + "_" + suffix
}
