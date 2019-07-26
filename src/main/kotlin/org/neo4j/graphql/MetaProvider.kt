package org.neo4j.graphql

import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry

interface MetaProvider {
    fun getNodeType(name: String?): NodeFacade?
    fun getValidTypeLabels(node: NodeFacade): List<String>
    fun getDirectiveArgument(directive: Directive?, name: String, defaultValue: String?): String?
}

class TypeRegistryMetaProvider(private val typeRegistry: TypeDefinitionRegistry) : MetaProvider {
    override fun getValidTypeLabels(node: NodeFacade): List<String> {
        val type = typeRegistry.types()[node.name()]
        if (type is ObjectTypeDefinition) {
            return listOf(type.getNodeType()!!.label())
        }
        if (type is InterfaceTypeDefinition) {
            return typeRegistry
                .getTypes(ObjectTypeDefinition::class.java)
                .filter { objectTypeDefinition -> objectTypeDefinition.implements.any { it.name() == node.name() } }
                .mapNotNull { it.getNodeType()?.label() }
        }
        return emptyList()
    }

    private fun getDirectiveDefinition(name: String?): DirectiveDefinition? {
        return when (name) {
            null -> null
            else -> typeRegistry.getDirectiveDefinition(name).orElse(null)
        }
    }

    override fun getNodeType(name: String?): NodeFacade? {
        return typeRegistry.getType(name).orElse(null)?.getNodeType()
    }

    override fun getDirectiveArgument(directive: Directive?, name: String, defaultValue: String?): String? {
        if (directive == null) return null
        return directive.getArgument(name)?.value?.toJavaValue()?.toString()
                ?: getDirectiveDefinition(directive.name)?.inputValueDefinitions?.first { it.name == name }?.defaultValue?.toJavaValue()?.toString()
                ?: defaultValue
                ?: throw IllegalStateException("No default value for ${directive.name}.$name")
    }

}