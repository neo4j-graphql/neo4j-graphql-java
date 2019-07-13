package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

val SCALAR_TYPES = listOf("String", "ID", "Boolean", "Int", "Float")

fun Type<Type<*>>.isScalar() = this.inner().name()?.let { SCALAR_TYPES.contains(it) } ?: false
fun Type<Type<*>>.name(): String? = if (this.inner() is TypeName) (this.inner() as TypeName).name else null

fun Type<Type<*>>.inner(): Type<Type<*>> = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    else -> this
}

fun Type<Type<*>>.render(nonNull: Boolean = true): String = when (this) {
    is ListType -> "[${this.type.render()}]"
    is NonNullType -> this.type.render() + (if (nonNull) "!" else "")
    is TypeName -> this.name
    else -> throw IllegalStateException("Can't render $this")
}

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)
fun GraphQLType.isScalar() = this.inner().let { it is GraphQLScalarType || it.name.startsWith("_Neo4j") }
fun GraphQLType.isRelationship() = this.inner().let { it is GraphQLObjectType }

fun GraphQLObjectType.hasRelationship(name: String) = this.getFieldDefinition(name)?.isRelationship() ?: false
fun GraphQLFieldDefinition.isRelationship() = this.type.isRelationship()

fun Field.aliasOrName() = (this.alias ?: this.name).quote()
fun Field.propertyName(fieldDefinition: GraphQLFieldDefinition) = (fieldDefinition.propertyDirectiveName()
        ?: this.name).quote()

fun GraphQLFieldDefinition.propertyDirectiveName() =
        this.definition.getDirective("property")?.getArgument("name")?.value?.toJavaValue()?.toString()

fun GraphQLFieldDefinition.isNativeId() = this.definition.getDirective("nativeId") != null

fun GraphQLFieldDefinition.cypherDirective(): Translator.Cypher? =
        this.definition.getDirective("cypher")?.let {
            @Suppress("UNCHECKED_CAST")
            Translator.Cypher(it.getArgument("statement").value.toJavaValue().toString(),
                    it.getArgument("params")?.value?.toJavaValue() as Map<String, Any?>? ?: emptyMap())
        }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

fun Directive.argumentString(name: String, schema: GraphQLSchema, defaultValue: String? = null): String {
    return this.getArgument(name)?.value?.toJavaValue()?.toString()
            ?: schema.getDirective(this.name).getArgument(name)?.defaultValue?.toString()
            ?: defaultValue
            ?: throw IllegalStateException("No default value for ${this.name}.$name")
}

fun Value<Value<*>>.toCypherString(): String = when (this) {
    is StringValue -> "'" + this.value + "'"
    is EnumValue -> "'" + this.name + "'"
    is NullValue -> "null"
    is BooleanValue -> this.isValue.toString()
    is FloatValue -> this.value.toString()
    is IntValue -> this.value.toString()
    is VariableReference -> "$" + this.name
    is ArrayValue -> this.values.joinToString(",", "[", "]") { it.toCypherString() }
    else -> throw IllegalStateException("Unhandled value $this")
}

fun Value<Value<*>>.toJavaValue(): Any? = when (this) {
    is StringValue -> this.value
    is EnumValue -> this.name
    is NullValue -> null
    is BooleanValue -> this.isValue
    is FloatValue -> this.value.toDouble()
    is IntValue -> this.value.longValueExact()
    is VariableReference -> this
    is ArrayValue -> this.values.map { it.toJavaValue() }.toList()
    is ObjectValue -> this.objectFields.map { it.name to it.value.toJavaValue() }.toMap()
    else -> throw IllegalStateException("Unhandled value $this")
}

fun paramName(variable: String, argName: String, value: Any?): String = when (value) {
    is VariableReference -> value.name
    else -> "$variable${argName.capitalize()}"
}

fun FieldDefinition.isID(): Boolean = this.type.name() == "ID"
fun FieldDefinition.isList(): Boolean = this.type is ListType
fun GraphQLFieldDefinition.isID(): Boolean = this.type.inner() == Scalars.GraphQLID
fun ObjectTypeDefinition.getFieldByType(typeName: String): FieldDefinition? = this.fieldDefinitions
    .firstOrNull { it.type.inner().name() == typeName }

fun GraphQLDirective.getRelationshipType(): String = this.getArgument("name").value.toString()
fun GraphQLDirective.getRelationshipDirection(): String = this.getArgument("direction")?.value?.toString() ?: "OUT"

