package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType

interface NodeFacade {
    fun name(): String
    fun interfaces(): List<String>
    fun fieldDefinitions(): List<FieldDefinition>
    fun getDirective(directiveName: String): Directive?
    fun getFieldDefinition(name: String?): FieldDefinition? = fieldDefinitions().firstOrNull() { it.name == name }
    fun hasRelationship(name: String): Boolean {
        val fieldDefinition = this.getFieldDefinition(name) ?: return false
        return fieldDefinition.type?.isScalar() == false // TODO
    }

    fun isRealtionType(): Boolean = this.getDirective(DirectiveConstants.RELATION) != null

    fun relationshipFor(name: String, metaProvider: MetaProvider): RelationshipInfo? {
        val field = this.getFieldDefinition(name)
                ?: throw IllegalArgumentException("$name is not defined on ${name()}")
        val fieldObjectType = metaProvider.getNodeType(field.type.name()!!) ?: return null

        // TODO direction is depending on source/target type

        val (relDirective, isRelFromType) = fieldObjectType.getDirective(DirectiveConstants.RELATION)?.let { it to true }
                ?: field.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")


        val relInfo = relDetails(fieldObjectType) { argName, defaultValue ->
            metaProvider.getDirectiveArgument(relDirective, argName, defaultValue)
        }

        val inverse = isRelFromType && fieldObjectType.getFieldDefinition(relInfo.startField)?.name != this.name()
        return if (inverse) relInfo.copy(out = relInfo.out?.let { !it }, startField = relInfo.endField, endField = relInfo.startField) else relInfo
    }

    fun allLabels(): String {
        return label(true)
    }

    fun label(includeAll: Boolean = false) = when {
        this.isRealtionType() ->
            getDirective(DirectiveConstants.RELATION)?.getArgument(DirectiveConstants.RELATION_NAME)?.value?.toJavaValue()?.toString()?.quote()
                    ?: name().quote()
        else -> when {
            includeAll -> (listOf(name()) + interfaces()).map { it.quote() }.joinToString(":")
            else -> name().quote()
        }
    }

    fun relationship(metaProvider: MetaProvider): RelationshipInfo? {
        val relDirective = this.getDirective(DirectiveConstants.RELATION) ?: return null
        val directiveResolver: (name: String, defaultValue: String?) -> String? = { argName, defaultValue ->
            metaProvider.getDirectiveArgument(relDirective, argName, defaultValue)
        }
        val relType = directiveResolver(DirectiveConstants.RELATION_NAME, "")!!
        val startField = directiveResolver(DirectiveConstants.RELATION_FROM, null)
        val endField = directiveResolver(DirectiveConstants.RELATION_TO, null)
        return RelationshipInfo(this, relType, null, startField, endField)
    }
}


interface NodeGraphQlFacade : NodeFacade {
    fun getValidTypeLabels(metaProvider: MetaProvider): List<String>
    fun getGraphQLFieldDefinition(name: String?): GraphQLFieldDefinition?
    override fun hasRelationship(name: String): Boolean = this.getGraphQLFieldDefinition(name)?.isRelationship()
            ?: false
}

interface NodeDefinitionFacade : NodeFacade {
    fun getType(): TypeDefinition<*>
}

data class ObjectNodeFacade(private val delegate: GraphQLObjectType) : NodeGraphQlFacade {
    override fun getGraphQLFieldDefinition(name: String?): GraphQLFieldDefinition? = delegate.getFieldDefinition(name)

    override fun name(): String = this.delegate.name
    override fun getValidTypeLabels(metaProvider: MetaProvider): List<String> = listOf(delegate.label())
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions.map { it.definition }
    override fun getDirective(directiveName: String): Directive? = this.delegate.definition.getDirective(directiveName)
    override fun getFieldDefinition(name: String?): FieldDefinition? = this.delegate.getFieldDefinition(name)?.definition
    override fun interfaces(): List<String> = delegate.interfaces.mapNotNull { it.name }
}

data class ObjectDefinitionNodeFacade(private val delegate: ObjectTypeDefinition) : NodeDefinitionFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
    override fun getType() = this.delegate
    override fun interfaces(): List<String> = delegate.implements.mapNotNull { name() }
}

data class InterfaceNodeFacade(private val delegate: GraphQLInterfaceType) : NodeGraphQlFacade {
    override fun getGraphQLFieldDefinition(name: String?): GraphQLFieldDefinition? = delegate.getFieldDefinition(name)

    override fun name(): String = this.delegate.name
    override fun getValidTypeLabels(metaProvider: MetaProvider): List<String> = metaProvider.getValidTypeLabels(this)
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions.map { it.definition }
    override fun getDirective(directiveName: String): Directive? = this.delegate.definition.getDirective(directiveName)
    override fun getFieldDefinition(name: String?): FieldDefinition? = this.delegate.getFieldDefinition(name).definition
    override fun interfaces(): List<String> = emptyList()
}

data class InterfaceDefinitionNodeFacade(private val delegate: InterfaceTypeDefinition) : NodeDefinitionFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
    override fun getType() = this.delegate
    override fun interfaces(): List<String> = emptyList()
}
