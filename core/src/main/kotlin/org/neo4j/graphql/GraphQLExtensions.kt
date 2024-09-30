package org.neo4j.graphql

import graphql.GraphQLContext
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.domain.directives.Annotations
import kotlin.reflect.KProperty1

fun Type<*>.name(): String = this.inner().name

fun Type<*>.inner(): TypeName = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    is TypeName -> this
    else -> throw IllegalStateException("Inner type is expected to hava a name")
}

fun Type<*>.isList(): Boolean = when (this) {
    is ListType -> true
    is NonNullType -> type.isList()
    else -> false
}

fun Type<*>.isRequired(): Boolean = when (this) {
    is NonNullType -> true
    else -> false
}

fun String.asRequiredType() = asType(true)
fun String.asType(required: Boolean = false) = TypeName(this).makeRequired(required)
fun String.wrapLike(type: Type<*>) = TypeName(this).wrapLike(type)
fun TypeName.wrapLike(type: Type<*>): Type<*> = when (type) {
    is ListType -> this.wrapLike(type.type).List
    is NonNullType -> this.wrapLike(type.type).NonNull
    is TypeName -> this
    else -> throw IllegalStateException("Unknown type")
}

val String.NonNull get() = asType(true)
val String.List get() = asType().List

val Type<*>.NonNull
    get() = when (this) {
        is NonNullType -> this
        else -> NonNullType(this)
    }

val Type<*>.List
    get() = when (this) {
        is ListType -> this
        else -> ListType(this)
    }

fun TypeName.makeRequired(required: Boolean = true): Type<*> = when (required) {
    true -> this.NonNull
    else -> this
}

fun Type<*>.isListElementRequired(): Boolean = when (this) {
    is ListType -> type is NonNullType
    is NonNullType -> type.isListElementRequired()
    else -> false
}

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)

fun SelectedField.aliasOrName(): String = (this.alias ?: this.name)

fun Any.toJavaValue() = when (this) {
    is Value<*> -> this.toJavaValue()
    else -> this
}

fun Value<*>.toJavaValue(): Any? = when (this) {
    is StringValue -> this.value
    is EnumValue -> this.name
    is NullValue -> null
    is BooleanValue -> this.isValue
    is FloatValue -> this.value.toDouble()
    is IntValue -> this.value.longValueExact()
    is VariableReference -> this
    is ArrayValue -> this.values.map { it.toJavaValue() }.toList()
    is ObjectValue -> this.objectFields.associate { it.name to it.value.toJavaValue() }
    else -> throw IllegalStateException("Unhandled value $this")
}

fun TypeDefinitionRegistry.queryType() = this.getTypeByName<ObjectTypeDefinition>(this.queryTypeName())
fun TypeDefinitionRegistry.mutationType() =
    this.getTypeByName<ObjectTypeDefinition>(this.mutationTypeName())

fun TypeDefinitionRegistry.subscriptionType() = this.getTypeByName<ObjectTypeDefinition>(this.subscriptionTypeName())
fun TypeDefinitionRegistry.queryTypeName() = this.getOperationType("query") ?: "Query"
fun TypeDefinitionRegistry.mutationTypeName() = this.getOperationType("mutation") ?: "Mutation"
fun TypeDefinitionRegistry.subscriptionTypeName() = this.getOperationType("subscription") ?: "Subscription"
fun TypeDefinitionRegistry.getOperationType(name: String) =
    this.schemaDefinition().unwrap()?.operationTypeDefinitions?.firstOrNull { it.name == name }?.typeName?.name

fun DataFetchingEnvironment.queryContext(): QueryContext =
    this.graphQlContext.get<QueryContext?>(QueryContext.KEY)
        ?: run {
            val queryContext = QueryContext()
            this.graphQlContext.put(QueryContext.KEY, queryContext)
            queryContext
        }

fun GraphQLContext.setQueryContext(ctx: QueryContext): GraphQLContext {
    this.put(QueryContext.KEY, ctx)
    return this
}


fun ObjectValue.get(name: String): Value<Value<*>>? = this.objectFields.firstOrNull { it.name == name }?.value

fun <T : Any?> Directive.readArgument(prop: KProperty1<*, T>): T? = readArgument(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T : Any?> Directive.readArgument(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T?): T? {
    return this.getArgument(prop.name)?.let { transformer.invoke(it.value) }
}

fun <T : Any?> Directive.readRequiredArgument(prop: KProperty1<*, T>): T = readRequiredArgument(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T : Any?> Directive.readRequiredArgument(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T?): T =
    readArgument(prop, transformer)
        ?: throw IllegalArgumentException("@${this.name} is missing the required argument ${prop.name}")


fun TypeDefinitionRegistry.getUnwrappedType(name: String?): TypeDefinition<TypeDefinition<*>>? =
    getType(name)?.unwrap()

inline fun <reified T : TypeDefinition<*>> TypeDefinitionRegistry.getTypeByName(name: String): T? =
    this.getType(name, T::class.java)?.unwrap()

fun ImplementingTypeDefinition<*>.getField(name: String): FieldDefinition? = fieldDefinitions.find { it.name == name }

fun TypeDefinitionRegistry.replace(definition: SDLDefinition<*>) {
    this.remove(definition)
    this.add(definition)
}

fun NodeDirectivesBuilder.addNonLibDirectives(directivesContainer: DirectivesContainer<*>) {
    this.directives(directivesContainer.directives.filterNot { Annotations.LIBRARY_DIRECTIVES.contains(it.name) })
}

inline fun <reified T> T.asCypherLiteral() = Cypher.literalOf<T>(this)
