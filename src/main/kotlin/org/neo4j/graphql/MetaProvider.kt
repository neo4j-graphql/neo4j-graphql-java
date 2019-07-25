package org.neo4j.graphql

import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
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
        when (name) {
            null -> return null
            else -> return typeRegistry.getDirectiveDefinition(name).orElse(null)
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

class SchemaMetaProvider(private val schema: GraphQLSchema) : MetaProvider {
    override fun getValidTypeLabels(node: NodeFacade): List<String> {
        val type = schema.getType(node.name())
        if (type is GraphQLObjectType) {
            return listOf(type.getNodeType()!!.label())
        }
        if (type is GraphQLInterfaceType) {
            return schema.getImplementations(type)
                .mapNotNull { it.getNodeType()?.label() }
        }
        return emptyList()
    }

    override fun getDirectiveArgument(directive: Directive?, name: String, defaultValue: String?): String? {
        if (directive == null) return null
        return directive.getArgument(name)?.value?.toJavaValue()?.toString()
                ?: schema.getDirective(directive.name)?.getArgument(name)?.defaultValue?.toString()
                ?: defaultValue
                ?: throw IllegalStateException("No default value for ${directive.name}.$name")
    }

    override fun getNodeType(name: String?): NodeFacade? {
        return schema.getType(name)?.getNodeType()
    }

}