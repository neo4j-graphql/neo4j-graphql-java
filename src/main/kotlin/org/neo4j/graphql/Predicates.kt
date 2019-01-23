package org.neo4j.graphql

import graphql.Scalars
import graphql.language.Directive
import graphql.language.Type
import graphql.schema.*
import org.neo4j.graphql.Predicate.Companion.resolvePredicate

interface Predicate {
    fun toExpression(variable: String, schema: GraphQLSchema) : Pair<String,Map<String,Any?>>

    companion object {
        fun resolvePredicate(name: String, value: Any?, type: GraphQLObjectType): Predicate {
            val (fieldName, op) = Operators.resolve(name, value)
            return if (type.hasRelationship(fieldName)) {
                if (value is Map<*, *>) RelationPredicate(fieldName, op, value, type)
                else if (value is IsNullOperator) IsNullPredicate(fieldName, op, type)
                else throw IllegalArgumentException("Input for $fieldName must be an filter-InputType")
            } else {
                ExpressionPredicate(fieldName, op, value)
            }
        }

        private fun isParam(value: String) = value.startsWith("{") && value.endsWith("}") || value.startsWith("\$")

        fun formatAnyValueCypher(value: Any?): String =
                when (value) {
                    null -> "null"
                    is String -> if (isParam(value)) value else "\"${value}\""
                    is Map<*, *> -> "{" + value.map { it.key.toString() + ":" + formatAnyValueCypher(it.value) }.joinToString(",") + "}"
                    is Iterable<*> -> "[" + value.map { formatAnyValueCypher(it) }.joinToString(",") + "]"
                    else -> value.toString()

                }
    }
}

fun toExpression(name: String, value: Any?, type: GraphQLObjectType): Predicate =
        if (name == "AND" || name == "OR")
            if (value is Iterable<*>) {
                CompoundPredicate(value.map { toExpression("AND", it, type) }, name)
            } else if (value is Map<*,*>){
                CompoundPredicate(value.map { (k,v) -> toExpression(k.toString(), v, type) }, name)
            } else {
                throw IllegalArgumentException("Unexpected value for filter: $value")
            }
        else {
            resolvePredicate(name, value, type)
        }

fun GraphQLObjectType.relationshipFor(name:String, schema: GraphQLSchema) : RelationshipInfo {
    val field = this.getFieldDefinition(name)
    val fieldObjectType = schema.getType(field.type.inner().name) as GraphQLObjectType
    // direction
    // label
    // out

    val (relDirective, relFromType) = fieldObjectType.definition.getDirective("relation")?.let { it to true }
            ?: field.definition.getDirective("relation")?.let { it to false }
            ?: throw IllegalStateException("Field $field needs an @relation directive")

    val (relType, outgoing, endField) = relDetails(relDirective, schema)

    return RelationshipInfo(fieldObjectType, relDirective, relType,  outgoing,endField, relFromType)
}

fun relDetails(relDirective: Directive, schema: GraphQLSchema): Triple<String,Boolean?,String> {
    val relType = relDirective.argumentString("name", schema)
    val outgoing =  when (relDirective.argumentString("direction", schema)) {
        "IN" -> false
        "BOTH" -> null
        "OUT" -> true
        else -> throw IllegalStateException("Unknown direction ${relDirective.argumentString("direction",schema)}")
    }
    val endField = if (outgoing == true) relDirective.argumentString("to", schema)
    else relDirective.argumentString("from", schema)
    return Triple(relType, outgoing, endField)
}

fun arrows(outgoing: Boolean?): Pair<String, String> {
    return when (outgoing) {
        false -> "<" to ""
        true -> "" to ">"
        null -> "" to ""
    }
}


data class RelationshipInfo(val objectType:GraphQLObjectType, val directive:Directive, val type:String, val out:Boolean?, val endField: String? = null, val relFromType: Boolean = false) {
    val arrows = arrows(out)
    val label = objectType.name
}

data class CompoundPredicate(val parts : List<Predicate>, val op : String = "AND") : Predicate {
    override fun toExpression(variable: String, schema: GraphQLSchema) : Pair<String,Map<String,Any?>> =
            parts.map { it.toExpression(variable,schema) }
                 .let { expressions ->
                        expressions.map { it.first }.joinNonEmpty(" "+op+" ","(",")") to
                        expressions.fold(emptyMap<String,Any?>()) { res, exp -> res + exp.second }
                    }
}

data class IsNullPredicate(val name:String, val op: Operators, val type: GraphQLObjectType) : Predicate {
    override fun toExpression(variable: String, schema: GraphQLSchema) : Pair<String,Map<String,Any?>> {
        val rel = type.relationshipFor(name, schema)
        val (left,right) = rel.arrows
        val not = if (op.not) "" else "NOT "
        return "$not($variable)$left-[:${rel.type}]-$right()" to emptyMap()
    }
}

data class ExpressionPredicate(val name:String, val op: Operators, val value:Any?) : Predicate {
    val not = if (op.not) "NOT " else ""
    override fun toExpression(variable: String, schema: GraphQLSchema) : Pair<String,Map<String,Any?>> {
        val paramName : String = "filter" + paramName(variable, name, value).capitalize()
        return "$not${variable}.$name ${op.op} \$${paramName}" to mapOf(paramName to value)
    }
}


data class RelationPredicate(val name: String, val op: Operators, val value: Map<*,*>, val type: GraphQLObjectType) : Predicate {
    val not = if (op.not) "NOT" else ""
    // (type)-[:TYPE]->(related) | pred] = 0/1/ > 0 | =
    // ALL/ANY/NONE/SINGLE(p in (type)-[:TYPE]->() WHERE pred(last(nodes(p)))
    // ALL/ANY/NONE/SINGLE(x IN [(type)-[:TYPE]->(o) | pred(o)] WHERE x)

    override fun toExpression(variable: String, schema: GraphQLSchema) : Pair<String,Map<String,Any?>> {
        val prefix = when (op) {
            Operators.EQ -> "ALL"
            Operators.NEQ -> "ALL" // bc of not
            else -> op.op
        }
        val rel = type.relationshipFor(name, schema)
        val (left,right) = rel.arrows
        val other = variable+"_"+rel.label
        val cond = other + "_Cond"
        val relGraphQLObjectType = schema.getType(rel.label) as GraphQLObjectType
        val (pred, params) = CompoundPredicate(value.map { it -> resolvePredicate(it.key.toString(), it.value,relGraphQLObjectType)}).toExpression(other, schema)
        return "$not $prefix(${cond} IN [($variable)$left-[:${rel.type}]-$right($other) | $pred] WHERE ${cond})" to params
    }
}

abstract class UnaryOperator
class IsNullOperator : UnaryOperator()

enum class Operators(val suffix:String, val op:String, val not :Boolean = false) {
    EQ("","="),
    IS_NULL("", ""),
    IS_NOT_NULL("", "", true),
    NEQ("not","=", true),
    GTE("gte",">="),
    GT("gt",">"),
    LTE("lte","<="),
    LT("lt","<"),

    NIN("not_in","IN", true),
    IN("in","IN"),
    NC("not_contains","CONTAINS", true),
    NSW("not_starts_with","STARTS WITH", true),
    NEW("not_ends_with","ENDS WITH", true),
    C("contains","CONTAINS"),
    SW("starts_with","STARTS WITH"),
    EW("ends_with","ENDS WITH"),

    SOME("some","ANY"),
    NONE("none","NONE"),
    ALL("every","ALL"),
    SINGLE("single","SINGLE")
    ;

    val list = op == "IN"

    companion object {
        val ops = enumValues<Operators>().sortedWith(Comparator.comparingInt<Operators> { it.suffix.length }).reversed()
        val allNames = ops.map { it.suffix }
        val allOps = ops.map { it.op }

        fun resolve(field: String, value: Any?) : Pair<String, Operators> {
            val fieldOperator = ops.find { field.endsWith("_" + it.suffix) }
            val unaryOperator = if (value is UnaryOperator) unaryOperatorOf(field, value) else Operators.EQ
            val op = fieldOperator ?: unaryOperator
            val name = if (op.suffix.isEmpty()) field else field.substring(0, field.length - op.suffix.length - 1)
            return name to op
        }

        private fun unaryOperatorOf(field: String, value: Any?): Operators =
                when (value) {
                    is IsNullOperator -> if (field.endsWith("_not")) IS_NOT_NULL else IS_NULL
                    else -> throw IllegalArgumentException("Unknown unary operator $value")
                }

        fun forType(type: GraphQLInputType) : List<Operators> =
                if (type == Scalars.GraphQLBoolean) listOf(EQ, NEQ)
                else if (type is GraphQLEnumType || type is GraphQLObjectType || type is GraphQLTypeReference) listOf(EQ, NEQ, IN, NIN)
                else listOf(EQ, NEQ, IN, NIN,LT,LTE,GT,GTE) +
                        if (type == Scalars.GraphQLString || type == Scalars.GraphQLID) listOf(C,NC, SW, NSW,EW,NEW) else emptyList()

        fun forType(type: Type) : List<Operators> =
                if (type.name() == "Boolean") listOf(EQ, NEQ)
                // todo list types
                // todo proper enum + object types and reference types
                else if (!type.isScalar()) listOf(EQ, NEQ, IN, NIN)
                else listOf(EQ, NEQ, IN, NIN,LT,LTE,GT,GTE) +
                        if (type.name() == "String" || type.name() == "ID") listOf(C,NC, SW, NSW,EW,NEW) else emptyList()

    }

    fun fieldName(fieldName: String) = if (this == EQ) fieldName else fieldName + "_" + suffix
}
