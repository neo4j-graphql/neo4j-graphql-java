package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase

abstract class BaseDataFetcher(
        val type: NodeFacade,
        val fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider
) : ProjectionBase(metaProvider), DataFetcher<Cypher> {

    val propertyFields: MutableMap<String, (Value<Value<*>>) -> List<Translator.CypherArgument>?> = mutableMapOf()
    val defaultFields: MutableMap<String, Value<Value<*>>> = mutableMapOf()

    init {
        val fieldsOfType = type.fieldDefinitions().map { it.name to it }.toMap()
        fieldDefinition
            .inputValueDefinitions
            .filterNot { listOf(FIRST, OFFSET, ORDER_BY, NATIVE_ID).contains(it.name) }
            .mapNotNull {
                if (it.defaultValue != null) {
                    defaultFields[it.name] = it.defaultValue
                }
                fieldsOfType[it.name]
            }
            .forEach { field: FieldDefinition ->
                val dynamicPrefix = field.dynamicPrefix(metaProvider)
                val callback = if (dynamicPrefix != null) {
                    { value: Value<Value<*>> ->
                        // maps each property of the map to the node
                        (value as? ObjectValue)?.objectFields?.map { argField ->
                            Translator.CypherArgument(
                                    field.name + argField.name.capitalize(),
                                    dynamicPrefix + argField.name,
                                    argField.value.toJavaValue()
                            )
                        }
                    }
                } else {
                    val propertyName = field.propertyDirectiveName() ?: field.name
                    { value: Value<Value<*>> ->
                        listOf(Translator.CypherArgument(field.name, propertyName, value.toJavaValue()))
                    }
                }
                propertyFields[field.name] = callback
            }
    }

    override fun get(environment: DataFetchingEnvironment?): Cypher {
        val field = environment?.getSource() as Field
        val ctx = environment.getContext() as Translator.Context
        if (field.name != fieldDefinition.name)
            throw IllegalArgumentException("Handler for ${fieldDefinition.name} cannot handle ${field.name}")
        val variable = field.aliasOrName().decapitalize()
        return generateCypher(
                variable,
                field,
                {
                    projectFields(variable, field, type, ctx, null)
                },
                ctx)
    }

    protected abstract fun generateCypher(variable: String,
            field: Field,
            projectionProvider: () -> Cypher,
            ctx: Translator.Context
    ): Cypher


    fun allLabels(): String = type.allLabels()

    fun label(includeAll: Boolean = false) = type.label(includeAll)


    protected fun properties(variable: String, arguments: List<Argument>): Cypher {
        val all = preparePredicateArguments(arguments)
        if (all.isEmpty()) {
            return Cypher.EMPTY
        }
        return Cypher(
                all.joinToString(", ", " { ", " }") { (argName, propertyName, value) -> "${propertyName.quote()}: \$${paramName(variable, argName, value)}" },
                all.map { (argName, _, value) -> paramName(variable, argName, value) to value }.toMap())
    }

    private fun preparePredicateArguments(arguments: List<Argument>): List<Translator.CypherArgument> {
        val predicates = arguments
            .mapNotNull { argument ->
                propertyFields[argument.name]?.invoke(argument.value)?.let { argument.name to it }
            }
            .toMap()

        val defaults = defaultFields
            .filter { !predicates.containsKey(it.key) }
            .flatMap { propertyFields[it.key]?.invoke(it.value) ?: emptyList() }
        return predicates.values.flatten() + defaults
    }

    companion object {
        fun getSelectQuery(
                variable: String,
                label: String?,
                idProperty: Argument?,
                idField: FieldDefinition,
                isRelation: Boolean,
                paramName: String? = idProperty?.let { paramName(variable, idProperty.name, idProperty.value) }
        ): Cypher {
            return when {
                idProperty != null && paramName != null -> {
                    val queryParams = mapOf(paramName to idProperty.value.toJavaValue())
                    if (idField.isNativeId()) {
                        if (isRelation) {
                            Cypher("()-[$variable:$label]->() WHERE ID($variable) = $$paramName", queryParams)
                        } else {
                            Cypher("($variable:$label) WHERE ID($variable) = $$paramName", queryParams)
                        }
                    } else {
                        // TODO handle @property aliasing
                        if (idProperty.value is ArrayValue) {
                            Cypher("($variable:$label) WHERE  $variable.${idField.name.quote()} IN $$paramName", queryParams)
                        } else {
                            Cypher("($variable:$label { ${idField.name.quote()}: $$paramName })", queryParams)
                        }
                    }
                }
                else -> Cypher.EMPTY
            }
        }

        fun input(name: String, type: Type<*>): InputValueDefinition {
            return InputValueDefinition.newInputValueDefinition().name(name).type(type).build()
        }

        fun createFieldDefinition(
                prefix: String,
                typeName: String,
                scalarFields: List<FieldDefinition>,
                forceOptionalProvider: (field: FieldDefinition) -> Boolean = { false }
        ): FieldDefinition.Builder {
            return FieldDefinition.newFieldDefinition()
                .name("$prefix$typeName")
                .inputValueDefinitions(getInputValueDefinitions(scalarFields, forceOptionalProvider))
                .type(NonNullType(TypeName(typeName)))
        }

        fun getInputValueDefinitions(scalarFields: List<FieldDefinition>, forceOptionalProvider: (field: FieldDefinition) -> Boolean): List<InputValueDefinition> {
            return scalarFields
                .map { input(it.name, if (forceOptionalProvider.invoke(it)) it.type.optional() else it.type) }
        }
    }
}