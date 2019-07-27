package org.neo4j.graphql

import graphql.Scalars
import graphql.language.FieldDefinition
import graphql.language.Type
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeReference
import org.neo4j.graphql.Predicate.Companion.resolvePredicate
import org.neo4j.graphql.handler.projection.ProjectionBase

interface Predicate {
    fun toExpression(variable: String, metaProvider: MetaProvider): Cypher

    companion object {
        fun resolvePredicate(name: String, value: Any?, type: NodeFacade, metaProvider: MetaProvider): Predicate {
            val (fieldName, op) = Operators.resolve(name, value)
            return if (type.hasRelationship(fieldName, metaProvider)) {
                when (value) {
                    is Map<*, *> -> RelationPredicate(fieldName, op, value, type)
                    is IsNullOperator -> IsNullPredicate(fieldName, op, type)
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
                    is Iterable<*> -> "[" + value.joinToString(",") { formatAnyValueCypher(it) } + "]"
                    else -> value.toString()

                }
    }
}

fun toExpression(name: String, value: Any?, type: NodeFacade, metaProvider: MetaProvider): Predicate =
        if (name == "AND" || name == "OR")
            when (value) {
                is Iterable<*> -> CompoundPredicate(value.map { toExpression("AND", it, type, metaProvider) }, name)
                is Map<*, *> -> CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type, metaProvider) }, name)
                else -> throw IllegalArgumentException("Unexpected value for filter: $value")
            }
        else {
            resolvePredicate(name, value, type, metaProvider)
        }

data class CompoundPredicate(val parts: List<Predicate>, val op: String = "AND") : Predicate {
    override fun toExpression(variable: String, metaProvider: MetaProvider): Cypher =
            parts.map { it.toExpression(variable, metaProvider) }
                .let { expressions ->
                    Cypher(
                            expressions.map { it.query }.joinNonEmpty(" $op ", "(", ")"),
                            expressions.fold(emptyMap()) { res, exp -> res + exp.params }
                    )
                }
}

data class IsNullPredicate(val fieldName: String, val op: Operators, val type: NodeFacade) : Predicate {
    override fun toExpression(variable: String, metaProvider: MetaProvider): Cypher {
        val rel = type.relationshipFor(fieldName, metaProvider) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val not = if (op.not) "" else "NOT "
        return Cypher("$not($variable)$left-[:${rel.relType}]-$right()")
    }
}

data class ExpressionPredicate(val name: String, val op: Operators, val value: Any?, val fieldDefinition: FieldDefinition) : Predicate {
    val not = if (op.not) "NOT " else ""
    override fun toExpression(variable: String, metaProvider: MetaProvider): Cypher {
        val paramName: String = ProjectionBase.FILTER + paramName(variable, name, value).capitalize()
        val field = if (fieldDefinition.isNativeId()) "ID($variable)" else "$variable.${name.quote()}"
        return Cypher("$not$field ${op.op} \$$paramName", mapOf(paramName to value))
    }
}


data class RelationPredicate(val fieldName: String, val op: Operators, val value: Map<*, *>, val type: NodeFacade) : Predicate {
    val not = if (op.not) "NOT" else ""
    // (type)-[:TYPE]->(related) | pred] = 0/1/ > 0 | =
    // ALL/ANY/NONE/SINGLE(p in (type)-[:TYPE]->() WHERE pred(last(nodes(p)))
    // ALL/ANY/NONE/SINGLE(x IN [(type)-[:TYPE]->(o) | pred(o)] WHERE x)

    override fun toExpression(variable: String, metaProvider: MetaProvider): Cypher {
        val prefix = when (op) {
            Operators.EQ -> "ALL"
            Operators.NEQ -> "ALL" // bc of not
            else -> op.op
        }
        val rel = type.relationshipFor(fieldName, metaProvider) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val other = variable + "_" + rel.typeName
        val cond = other + "_Cond"
        val relNodeType = metaProvider.getNodeType(rel.typeName)
                ?: throw IllegalArgumentException("${rel.typeName} not found")
        val (pred, params) = CompoundPredicate(value.map { resolvePredicate(it.key.toString(), it.value, relNodeType, metaProvider) }).toExpression(other, metaProvider)
        return Cypher("$not $prefix($cond IN [($variable)$left-[:${rel.relType.quote()}]-$right($other) | $pred] WHERE $cond)", params)
    }
}

abstract class UnaryOperator
class IsNullOperator : UnaryOperator()

@Suppress("unused")
enum class Operators(val suffix: String, val op: String, val not: Boolean = false) {
    EQ("", "="),
    IS_NULL("", ""),
    IS_NOT_NULL("", "", true),
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
    NONE("none", "NONE"),
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

        fun forType(type: Type<Type<*>>): List<Operators> =
                if (type.name() == "Boolean") listOf(EQ, NEQ)
                // todo list types
                // todo proper enum + object types and reference types
                else if (!type.isScalar()) listOf(EQ, NEQ, IN, NIN)
                else listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                        if (type.name() == "String" || type.name() == "ID") listOf(C, NC, SW, NSW, EW, NEW) else emptyList()

    }

    fun fieldName(fieldName: String) = if (this == EQ) fieldName else fieldName + "_" + suffix
}
