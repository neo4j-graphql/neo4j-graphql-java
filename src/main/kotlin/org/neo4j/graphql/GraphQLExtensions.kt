package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_BOTH
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_IN
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_OUT
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_FROM
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO
import org.neo4j.graphql.handler.projection.ProjectionBase

val SCALAR_TYPES = listOf("String", "ID", "Boolean", "Int", "Float")

fun Type<Type<*>>.isScalar() = this.inner().name()?.let { SCALAR_TYPES.contains(it) } ?: false
fun Type<Type<*>>.name(): String? = if (this.inner() is TypeName) (this.inner() as TypeName).name else null
fun Type<Type<*>>.inner(): Type<Type<*>> = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    else -> this
}

fun Type<*>.optional(): Type<*> = when (this) {
    is ListType -> this
    is NonNullType -> this.type
    is TypeName -> this
    else -> throw IllegalStateException("Can't nonNull $this")
}

fun Type<Type<*>>.inputType(): Type<*> = if (this.isNeo4jType()) TypeName(this.name() + "Input") else this

fun Type<*>.isList() = this is ListType || (this is NonNullType && this.type is ListType)

fun TypeDefinition<*>.getNodeType(): NodeFacade? {
    return when (this) {
        is ObjectTypeDefinition -> ObjectDefinitionNodeFacade(this)
        is InterfaceTypeDefinition -> InterfaceDefinitionNodeFacade(this)
        else -> null
    }
}

fun Type<Type<*>>.isNeo4jType(): Boolean = this.inner().name()?.startsWith("_Neo4j") == true
fun ObjectTypeDefinition.isNeo4jType(): Boolean = this.name.startsWith("_Neo4j")
fun FieldDefinition.isNeo4jType(): Boolean = this.type.isNeo4jType()
fun NodeFacade.isNeo4jType(): Boolean = this.name().startsWith("_Neo4j")

fun relDetails(type: NodeFacade,
        directiveResolver: (name: String, defaultValue: String?) -> String?): RelationshipInfo {
    val relType = directiveResolver(RELATION_NAME, "")!!
    val outgoing = when (directiveResolver(RELATION_DIRECTION, null)) {
        RELATION_DIRECTION_IN -> false
        RELATION_DIRECTION_BOTH -> null
        RELATION_DIRECTION_OUT -> true
        else -> throw IllegalStateException("Unknown direction ${directiveResolver(RELATION_DIRECTION, null)}")
    }
    return RelationshipInfo(type,
            relType,
            outgoing,
            directiveResolver(RELATION_FROM, null),
            directiveResolver(RELATION_TO, null))
}

data class RelationshipInfo(
        val type: NodeFacade,
        val relType: String,
        val out: Boolean?,
        val startField: String? = null,
        val endField: String? = null,
        val isRelFromType: Boolean = false
) {
    data class RelatedField(
            val argumentName: String,
            val field: FieldDefinition,
            val declaringType: NodeFacade
    )

    val arrows = when (out) {
        false -> "<" to ""
        true -> "" to ">"
        null -> "" to ""
    }

    val typeName: String get() = this.type.name()

    fun getStartFieldId(metaProvider: MetaProvider) = getRelatedIdField(this.startField, metaProvider)

    fun getEndFieldId(metaProvider: MetaProvider) = getRelatedIdField(this.endField, metaProvider)

    private fun getRelatedIdField(relFieldName: String?, metaProvider: MetaProvider): RelatedField? {
        if (relFieldName == null) return null
        val relFieldDefinition = type.fieldDefinitions().find { it.name == relFieldName }
                ?: throw IllegalArgumentException("field $relFieldName does not exists on ${type.name()}")
        val relType = metaProvider.getNodeType(relFieldDefinition.type.name())
                ?: throw IllegalArgumentException("type ${relFieldDefinition.type.name()} not found")
        return relType.fieldDefinitions().filter { it.isID() }
            .map { RelatedField("${relFieldName}_${it.name}", it, relType) }
            .firstOrNull()
    }
}

fun Field.aliasOrName() = (this.alias ?: this.name).quote()
fun Field.propertyName(fieldDefinition: FieldDefinition) = (fieldDefinition.propertyDirectiveName()
        ?: this.name).quote()

fun FieldDefinition.propertyDirectiveName() =
        getDirective(PROPERTY)?.getArgument(PROPERTY_NAME)?.value?.toJavaValue()?.toString()

fun FieldDefinition.cypherDirective(): Cypher? =
        this.getDirective(CYPHER)?.let {
            @Suppress("UNCHECKED_CAST")
            (Cypher(it.getArgument(CYPHER_STATEMENT).value.toJavaValue().toString(),
                    it.getArgument("params")?.value?.toJavaValue() as Map<String, Any?>? ?: emptyMap()))
        }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

fun Value<Value<*>>.toCypherString(): String = when (this) {
    is StringValue -> "'" + this.value + "'"
    is EnumValue -> "'" + this.name + "'"
    is NullValue -> "null"
    is BooleanValue -> this.isValue.toString()
    is FloatValue -> this.value.toString()
    is IntValue -> this.value.toString()
    is VariableReference -> "$" + this.name
    is ArrayValue -> this.values.map{ it.toCypherString() }.joinToString(",", "[", "]")
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
fun FieldDefinition.isNativeId() = this.name == ProjectionBase.NATIVE_ID

fun FieldDefinition.isList(): Boolean = this.type.isList()
fun ObjectTypeDefinition.getFieldByType(typeName: String): FieldDefinition? = this.fieldDefinitions
    .firstOrNull { it.type.inner().name() == typeName }
