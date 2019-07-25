package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC_PREFIX
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_BOTH
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_IN
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_OUT
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_FROM
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

val SCALAR_TYPES = listOf("String", "ID", "Boolean", "Int", "Float", "Object")

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
fun Type<*>.isList() = this is ListType || (this is NonNullType && this.type is ListType)

fun GraphQLType.isScalar() = this.inner().let { it is GraphQLScalarType || it.name.startsWith("_Neo4j") }
fun GraphQLType.isRelationship() = this.inner().let { it is GraphQLObjectType }
fun GraphQLType.isRelationshipType() = this.inner().let { it is GraphQLObjectType && it.getDirective(RELATION) != null }
fun GraphQLType.label(includeAll: Boolean = false) = when {
    this.isRelationshipType() && this is GraphQLObjectType -> this.getRelationshipName()?.quote() ?: this.name.quote()
    else -> when {
        includeAll && this is GraphQLObjectType -> (listOf(this.name) + this.interfaces.map { it.name }).map { it.quote() }.joinToString(":")
        else -> this.name.quote()
    }
}

fun GraphQLObjectType.getRelationshipName() = this.getDirective(RELATION)?.getArgument(RELATION_NAME)?.value?.toString()
fun ObjectTypeDefinition.relationship(defaultValueProvider: (directiveName: String, arg: String) -> String?): RelationshipInfo? {
    val relDirective = this.getDirective(RELATION) ?: return null
    val directiveResolver: (name: String, defaultValue: String?) -> String = { param, defaultValue ->
        relDirective.getArgument(param)?.value?.toJavaValue()?.toString()
                ?: defaultValueProvider(relDirective.name, param)
                ?: defaultValue
                ?: throw IllegalStateException("No default value for ${relDirective.name}.$param")
    }
    val relType = directiveResolver(RELATION_NAME, "")
    val startField = directiveResolver(RELATION_FROM, null)
    val endField = directiveResolver(RELATION_TO, null)
    return RelationshipInfo(ObjectDefinitionNodeFacade(this), relType, null, startField, endField)
}

fun GraphQLObjectType.relationship(schema: GraphQLSchema): RelationshipInfo? {
    return this.definition.relationship { directiveName, arg -> schema.getDirective(directiveName)?.getArgument(arg)?.defaultValue?.toString() }
}

fun GraphQLType.getNodeType(): NodeGraphQlFacade? {
    return when (this) {
        is GraphQLObjectType -> ObjectNodeFacade(this)
        is GraphQLInterfaceType -> InterfaceNodeFacade(this)
        else -> null
    }
}

fun GraphQLSchema.getNodeType(name: String?): NodeGraphQlFacade? = this.getType(name)?.getNodeType()

fun TypeDefinition<*>.getNodeType(): NodeDefinitionFacade? {
    return when (this) {
        is ObjectTypeDefinition -> ObjectDefinitionNodeFacade(this)
        is InterfaceTypeDefinition -> InterfaceDefinitionNodeFacade(this)
        else -> null
    }
}

fun TypeDefinitionRegistry.getNodeType(name: String?): NodeFacade? = this.getType(name).orElse(null)?.getNodeType()


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

fun arrows(outgoing: Boolean?): Pair<String, String> {
    return when (outgoing) {
        false -> "<" to ""
        true -> "" to ">"
        null -> "" to ""
    }
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

    val arrows = arrows(out)

    val typeName: String get() = this.type.name()

    fun getStartFieldId(metaProvider: MetaProvider) = getRelatedIdField(this.startField, metaProvider)

    fun getEndFieldId(metaProvider: MetaProvider) = getRelatedIdField(this.endField, metaProvider)

    fun getRelatedIdField(relFieldName: String?, metaProvider: MetaProvider): RelatedField? {
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

fun GraphQLFieldDefinition.isRelationship() = this.type.isRelationship()

fun Field.aliasOrName() = (this.alias ?: this.name).quote()
fun Field.propertyName(fieldDefinition: FieldDefinition) = (fieldDefinition.propertyDirectiveName()
        ?: this.name).quote()

@Deprecated(message = "cleanup")
fun Field.propertyName(fieldDefinition: GraphQLFieldDefinition) = (fieldDefinition.propertyDirectiveName()
        ?: this.name).quote()


fun GraphQLFieldDefinition.propertyDirectiveName() =
        definition.getDirective(PROPERTY)?.getArgument(PROPERTY_NAME)?.value?.toJavaValue()?.toString()

fun GraphQLFieldDefinition.isNativeId() = definition.isNativeId()

fun FieldDefinition.dynamicPrefix(metaProvider: MetaProvider): String? = metaProvider.getDirectiveArgument(getDirective(DYNAMIC), DYNAMIC_PREFIX, null)

@Deprecated(message = "cleanup")
fun GraphQLFieldDefinition.dynamicPrefix(metaProvider: MetaProvider): String? = metaProvider.getDirectiveArgument(definition.getDirective(DYNAMIC), DYNAMIC_PREFIX, null)

fun FieldDefinition.cypherDirective(): Translator.Cypher? =
        this.getDirective(CYPHER)?.let {
            @Suppress("UNCHECKED_CAST")
            Translator.Cypher(it.getArgument(CYPHER_STATEMENT).value.toJavaValue().toString(),
                    it.getArgument("params")?.value?.toJavaValue() as Map<String, Any?>? ?: emptyMap())
        }

@Deprecated(message = "cleanup")
fun GraphQLFieldDefinition.cypherDirective(): Translator.Cypher? =
        this.definition.getDirective(CYPHER)?.let {
            @Suppress("UNCHECKED_CAST")
            Translator.Cypher(it.getArgument(CYPHER_STATEMENT).value.toJavaValue().toString(),
                    it.getArgument("params")?.value?.toJavaValue() as Map<String, Any?>? ?: emptyMap())
        }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

@Deprecated(message = "cleanup")
fun Directive.argumentString(name: String, schema: GraphQLSchema, defaultValue: String? = null): String {
    return this.getArgument(name)?.value?.toJavaValue()?.toString()
            ?: schema.getDirective(this.name).getArgument(name)?.defaultValue?.toString()
            ?: defaultValue
            ?: throw IllegalStateException("No default value for ${this.name}.$name")
}

fun Directive.argumentString(name: String, typeDefinitionRegistry: TypeDefinitionRegistry, defaultValue: String? = null): String {
    return this.getArgument(name)?.value?.toJavaValue()?.toString()
            ?: typeDefinitionRegistry.getDirectiveDefinition(this.name)
                .map { it.inputValueDefinitions.find { it.name == name }?.defaultValue?.toJavaValue()?.toString() }
                .orElse(defaultValue)
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
fun FieldDefinition.isNativeId() = this.name == "_id"

fun FieldDefinition.propertyDirectiveName() =
        getDirective(PROPERTY)?.getArgument(PROPERTY_NAME)?.value?.toJavaValue()?.toString()


fun FieldDefinition.isList(): Boolean = this.type is ListType
fun GraphQLFieldDefinition.isID(): Boolean = this.type.inner() == Scalars.GraphQLID
fun ObjectTypeDefinition.getFieldByType(typeName: String): FieldDefinition? = this.fieldDefinitions
    .firstOrNull { it.type.inner().name() == typeName }

fun ObjectTypeDefinition.isRealtionType(): Boolean = this.getDirective(RELATION) != null

fun GraphQLDirective.getRelationshipType(): String = this.getArgument(RELATION_NAME).value.toString()
fun GraphQLDirective.getRelationshipDirection(): String = this.getArgument(RELATION_DIRECTION)?.value?.toString()
        ?: RELATION_DIRECTION_OUT
