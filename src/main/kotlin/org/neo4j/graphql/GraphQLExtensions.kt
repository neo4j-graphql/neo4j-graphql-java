package org.neo4j.graphql

import graphql.language.*
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType

fun GraphQLType.inner() : GraphQLType = when(this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)

fun Field.aliasOrName() = (this.alias ?: this.name).quote()

fun String.quote() = this // do we actually need this? if (isJavaIdentifier()) this else '`'+this+'`'

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

fun Directive.argumentString(name:String, schema:GraphQLSchema) : String {
    return this.getArgument(name)?.value?.toJavaValue()?.toString()
            ?: schema.getDirective(this.name).getArgument(name)?.defaultValue?.toString()
            ?: throw IllegalStateException("No default value for ${this.name}.${name}")
}

fun Value.toCypherString(): String = when (this) {
    is StringValue -> "'"+this.value+"'"
    is EnumValue -> "'"+this.name+"'"
    is NullValue -> "null"
    is BooleanValue -> this.isValue.toString()
    is FloatValue -> this.value.toString()
    is IntValue -> this.value.toString()
    is VariableReference -> "$"+this.name
    is ArrayValue -> this.values.map { it.toCypherString() }.joinToString(",","[","]")
    else -> throw IllegalStateException("Unhandled value "+this)
}

fun Value.toJavaValue(): Any? = when (this) {
    is StringValue -> this.value
    is EnumValue -> this.name
    is NullValue -> null
    is BooleanValue -> this.isValue
    is FloatValue -> this.value
    is IntValue -> this.value
    is VariableReference -> this
    is ArrayValue -> this.values.map { it.toJavaValue() }.toList()
    is ObjectValue -> this.objectFields.map { it.name to it.value.toJavaValue() }.toMap()
    else -> throw IllegalStateException("Unhandled value "+this)
}
fun paramName(variable: String, argName: String, value: Any?) = when (value) {
    is VariableReference ->  value.name
    else -> "$variable${argName.capitalize()}"
}
