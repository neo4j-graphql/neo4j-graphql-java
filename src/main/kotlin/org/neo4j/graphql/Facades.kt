package org.neo4j.graphql

import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition

interface NodeFacade {
    fun name(): String
    fun interfaces(): List<String>
    fun fieldDefinitions(): List<FieldDefinition>
    fun getDirective(directiveName: String): Directive?
    fun getFieldDefinition(name: String?): FieldDefinition? = fieldDefinitions().firstOrNull { it.name == name }
    fun hasRelationship(name: String, metaProvider: MetaProvider): Boolean {
        val fieldDefinition = this.getFieldDefinition(name) ?: return false
        return metaProvider.getNodeType(fieldDefinition.type.name()) != null
    }

    fun relevantFields(): List<FieldDefinition> = fieldDefinitions()
        .filter { it.type.isScalar() || it.isNeo4jType()}
        .sortedByDescending { it.isID() }

    fun isRelationType(): Boolean = this.getDirective(DirectiveConstants.RELATION) != null

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
        this.isRelationType() ->
            getDirective(DirectiveConstants.RELATION)?.getArgument(DirectiveConstants.RELATION_NAME)?.value?.toJavaValue()?.toString()?.quote()
                    ?: name().quote()
        else -> when {
            includeAll -> (listOf(name()) + interfaces()).joinToString(":") { it.quote() }
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

data class ObjectDefinitionNodeFacade(private val delegate: ObjectTypeDefinition) : NodeFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
    override fun interfaces(): List<String> = delegate.implements.mapNotNull { it.name() }
}

data class InterfaceDefinitionNodeFacade(private val delegate: InterfaceTypeDefinition) : NodeFacade {
    override fun name(): String = this.delegate.name
    override fun fieldDefinitions(): List<FieldDefinition> = this.delegate.fieldDefinitions
    override fun getDirective(directiveName: String): Directive? = this.delegate.getDirective(directiveName)
    override fun interfaces(): List<String> = emptyList()
}
