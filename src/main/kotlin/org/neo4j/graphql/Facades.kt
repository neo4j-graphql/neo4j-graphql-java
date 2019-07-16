package org.neo4j.graphql

import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.*

interface NodeFacade {
    fun name(): String
    fun fieldDefinitions(): List<FieldDefinition>
    fun getDirective(directiveName: String): Directive?
}


interface NodeGraphQlFacade : NodeFacade {
    fun getFieldDefinition(name: String?): GraphQLFieldDefinition?
    fun getValidTypeLabels(schema: GraphQLSchema): List<String>

    fun hasRelationship(name: String) = this.getFieldDefinition(name)?.isRelationship() ?: false
    fun relationshipFor(name: String, schema: GraphQLSchema): RelationshipInfo? {
        return relationshipFor(name, schema::getNodeType, schema::getDirective)
    }

    fun relationshipFor(name: String, typeResolver: (String) -> NodeGraphQlFacade?, definitionProvider: (String) -> GraphQLDirective): RelationshipInfo? {
        val field = this.getFieldDefinition(name) ?: throw IllegalArgumentException("$name is not defined on ${name()}")
        val fieldObjectType = typeResolver(field.type.inner().name) ?: return null

        // TODO direction is depending on source/target type

        val (relDirective, isRelFromType) = fieldObjectType.getDirective(DirectiveConstants.RELATION)?.let { it to true }
                ?: field.definition.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")


        val relInfo = relDetails(fieldObjectType) { argName, defaultValue ->
            relDirective.getArgument(argName)?.value?.toJavaValue()?.toString()
                    ?: definitionProvider(relDirective.name).getArgument(argName)?.defaultValue?.toString()
                    ?: defaultValue
                    ?: throw IllegalStateException("No default value for ${relDirective.name}.$argName")
        }

        val inverse = isRelFromType && fieldObjectType.getFieldDefinition(relInfo.startField)?.name != this.name()
        return if (inverse) relInfo.copy(out = relInfo.out?.let { !it }, startField = relInfo.endField, endField = relInfo.startField) else relInfo
    }
}

interface NodeDefinitionFacade : NodeFacade {
    fun isRealtionType(): Boolean = this.getDirective(DirectiveConstants.RELATION) != null
}

data class ObjectNodeFacade(private val delegate: GraphQLObjectType) : NodeGraphQlFacade {
    override fun name(): String = this.delegate.name
    override fun getValidTypeLabels(schema: GraphQLSchema): List<String> = listOf(delegate.label())
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions.map { it.definition }
    override fun getDirective(directiveName: String): Directive? = this.delegate.definition.getDirective(directiveName)
    override fun getFieldDefinition(name: String?): GraphQLFieldDefinition? = this.delegate.getFieldDefinition(name)
}

data class ObjectDefinitionNodeFacade(private val delegate: ObjectTypeDefinition) : NodeDefinitionFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
}

data class InterfaceNodeFacade(private val delegate: GraphQLInterfaceType) : NodeGraphQlFacade {
    override fun name(): String = this.delegate.name
    override fun getValidTypeLabels(schema: GraphQLSchema): List<String> = schema.getImplementations(delegate).map { it.label() }
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions.map { it.definition }
    override fun getDirective(directiveName: String): Directive? = this.delegate.definition.getDirective(directiveName)
    override fun getFieldDefinition(name: String?): GraphQLFieldDefinition? = this.delegate.getFieldDefinition(name)
}

data class InterfaceDefinitionNodeFacade(private val delegate: InterfaceTypeDefinition) : NodeDefinitionFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
}
