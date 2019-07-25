package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*

abstract class BaseDataFetcher(
        val type: NodeFacade,
        val fieldDefinition: FieldDefinition,
        val typeDefinitionRegistry: TypeDefinitionRegistry,
        val projectionRepository: ProjectionRepository
) : DataFetcher<Translator.Cypher> {

    val propertyFields: MutableMap<String, (Value<Value<*>>) -> List<Translator.CypherArgument>?> = mutableMapOf()
    val defaultFields: MutableMap<String, Value<Value<*>>> = mutableMapOf()

    init {
        val fieldsOfType = type.fieldDefinitions().map { it.name to it }.toMap()
        fieldDefinition
            .inputValueDefinitions
            // TODO constants
            .filterNot { listOf("first", "offset", "orderBy", "_id").contains(it.name) }
            .mapNotNull {
                if (it.defaultValue != null) {
                    defaultFields[it.name] = it.defaultValue
                }
                fieldsOfType[it.name]
            }
            .forEach { field: FieldDefinition ->
                val dynamicPrefix = field.dynamicPrefix(TypeRegistryMetaProvider(typeDefinitionRegistry))
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
                propertyFields.put(field.name, callback)
            }
    }

    override fun get(environment: DataFetchingEnvironment?): Translator.Cypher {
        return toQuery(environment?.getSource() as Field, environment.getContext() as Translator.Context)
    }

    fun toQuery(field: Field, ctx: Translator.Context): Translator.Cypher {
        if (field.name != fieldDefinition.name)
            throw IllegalArgumentException("Handler for ${fieldDefinition.name} cannot handle ${field.name}")
        val variable = field.aliasOrName().decapitalize()
        return generateCypher(
                variable,
                field,
                {
                    projectionRepository.getHandler(type.name())?.projectFields(variable, field, type, ctx, null)
                            ?: Translator.Cypher.EMPTY
                },
                ctx)
    }

    protected abstract fun generateCypher(variable: String,
            field: Field,
            projectionProvider: () -> Translator.Cypher,
            ctx: Translator.Context
    ): Translator.Cypher


    fun allLabels(): String = type.allLabels()

    fun label(includeAll: Boolean = false) = type.label(includeAll)


    protected fun properties(variable: String, arguments: List<Argument>): Translator.Cypher {
        val all = preparePredicateArguments(arguments)
        if (all.isEmpty()) {
            return Translator.Cypher.EMPTY
        }
        return Translator.Cypher(
                all.joinToString(", ", " { ", " }") { (argName, propertyName, value) -> "${propertyName.quote()}: \$${paramName(variable, argName, value)}" },
                all.map { (argName, _, value) -> paramName(variable, argName, value) to value }.toMap())
    }

    protected fun preparePredicateArguments(arguments: List<Argument>): List<Translator.CypherArgument> {
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
        ): Translator.Cypher {
            return when {
                idProperty != null && paramName != null -> {
                    val queryParams = mapOf(paramName to idProperty.value.toJavaValue())
                    if (idField.isNativeId()) {
                        if (isRelation) {
                            Translator.Cypher("()-[$variable:$label]->() WHERE ID($variable) = $$paramName", queryParams)
                        } else {
                            Translator.Cypher("($variable:$label) WHERE ID($variable) = $$paramName", queryParams)
                        }
                    } else {
                        // TODO handle @property aliasing
                        if (idProperty.value is ArrayValue) {
                            Translator.Cypher("($variable:$label) WHERE  $variable.${idField.name.quote()} IN $$paramName", queryParams)
                        } else {
                            Translator.Cypher("($variable:$label { ${idField.name.quote()}: $$paramName })", queryParams)
                        }
                    }
                }
                else -> Translator.Cypher.EMPTY
            }
        }
    }

    fun cypherDirective(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Translator.Cypher, additionalArgs: List<Translator.CypherArgument>): Translator.Cypher {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val (query, args) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, additionalArgs)
        return Translator.Cypher("apoc.cypher.runFirstColumn$suffix($query)", args)
    }

    fun cypherDirectiveQuery(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Translator.Cypher, additionalArgs: List<Translator.CypherArgument>): Translator.Cypher {
        val args = additionalArgs + prepareFieldArguments(fieldDefinition, field.arguments)
        val argParams = args.map { '$' + it.name + " AS " + it.name }.joinNonEmpty(", ")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.escapedQuery()
        val argString = (args.map { it.name + ':' + if (it.name == "this") it.value else ('$' + paramName(variable, it.name, it.value)) }).joinToString(", ", "{ ", " }")
        return Translator.Cypher("'$query', $argString", args.filter { it.name != "this" }.associate { paramName(variable, it.name, it.value) to it.value })
    }

    fun prepareFieldArguments(field: FieldDefinition, arguments: List<Argument>): List<Translator.CypherArgument> {
        // if (arguments.isEmpty()) return emptyList()
        val predicates = arguments.map { it.name to Translator.CypherArgument(it.name, it.name, it.value.toJavaValue()) }.toMap()
        val defaults = field.inputValueDefinitions.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { Translator.CypherArgument(it.name, it.name, it.defaultValue.toJavaValue()) }
        return predicates.values + defaults
    }

    fun orderBy(variable: String, args: MutableList<Argument>): String {
        val arg = args.find { it.name == "orderBy" }
        val values = arg?.value?.let { it ->
            when (it) {
                is ArrayValue -> it.values.map { it.toJavaValue().toString() }
                is EnumValue -> listOf(it.name)
                is StringValue -> listOf(it.value)
                else -> null
            }
        }
        return if (values == null) ""
        else " ORDER BY " + values
            .map { it.split("_") }
            .joinToString(", ") { "$variable.${it[0]} ${it[1].toUpperCase()}" }
    }

}