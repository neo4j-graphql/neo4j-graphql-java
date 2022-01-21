package org.neo4j.graphql.merge

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.getTypeByName
import org.neo4j.graphql.name
import org.neo4j.graphql.replace

object TypeDefinitionRegistryMerger {
    fun mergeExtensions(registry: TypeDefinitionRegistry) {

        registry.objectTypeExtensions().values.flatten().forEach {
            val existing = registry.getTypeByName<ObjectTypeDefinition>(it.name)
            if (existing == null) {
                registry.add(it.merge())
            } else {
                registry.replace(existing.merge(it))
            }
            registry.remove(it)
        }
        registry.interfaceTypeExtensions().values.flatten().forEach {
            val existing = registry.getTypeByName<InterfaceTypeDefinition>(it.name)
            if (existing == null) {
                registry.add(it.merge())
            } else {
                registry.replace(existing.merge(it))
            }
            registry.remove(it)
        }
        registry.unionTypeExtensions().values.flatten().forEach {
            val existing = registry.getTypeByName<UnionTypeDefinition>(it.name)
            if (existing == null) {
                registry.add(it.merge())
            } else {
                registry.replace(existing.merge(it))
            }
            registry.remove(it)
        }
        registry.inputObjectTypeExtensions().values.flatten().forEach {
            val existing = registry.getTypeByName<InputObjectTypeDefinition>(it.name)
            if (existing == null) {
                registry.add(it.merge())
            } else {
                registry.replace(existing.merge(it))
            }
            registry.remove(it)
        }
    }


    private fun ObjectTypeDefinition.merge(other: ObjectTypeDefinition? = null): ObjectTypeDefinition =
        ObjectTypeDefinition
            .newObjectTypeDefinition()
            .mergeNode(this, other)
            .mergeDirectives(this, other)
            .name(this.name)
            .fieldDefinitions(mergeNamedNodes(this.fieldDefinitions, other?.fieldDefinitions))
            .implementz(mergeTypeList(this.implements, other?.implements))
            .build()

    private fun InterfaceTypeDefinition.merge(other: InterfaceTypeDefinition? = null): InterfaceTypeDefinition =
        InterfaceTypeDefinition
            .newInterfaceTypeDefinition()
            .mergeNode(this, other)
            .mergeDirectives(this, other)
            .name(this.name)
            .definitions(mergeNamedNodes(this.fieldDefinitions, other?.fieldDefinitions))
            .implementz(mergeTypeList(this.implements, other?.implements))
            .build()

    private fun UnionTypeDefinition.merge(other: UnionTypeDefinition? = null): UnionTypeDefinition =
        UnionTypeDefinition
            .newUnionTypeDefinition()
            .mergeNode(this, other)
            .mergeDirectives(this, other)
            .name(this.name)
            .memberTypes(mergeTypeList(this.memberTypes, other?.memberTypes))
            .build()

    private fun InputObjectTypeDefinition.merge(other: InputObjectTypeDefinition? = null): InputObjectTypeDefinition =
        InputObjectTypeDefinition
            .newInputObjectDefinition()
            .mergeNode(this, other)
            .mergeDirectives(this, other)
            .name(this.name)
            .inputValueDefinitions(mergeNamedNodes(this.inputValueDefinitions, other?.inputValueDefinitions))
            .build()

    private fun <T : NamedNode<*>> mergeNamedNodes(
        current: List<T>,
        other: List<T>?
    ): List<T> {
        if (other == null) {
            return current
        }
        val result = current.toMutableList()
        other.forEach { field ->
            if (result.find { it.name == field.name } == null) result.add(field)
        }
        return result
    }

    private fun mergeTypeList(
        current: List<Type<*>>,
        other: List<Type<*>>?
    ): List<Type<*>> {
        if (other == null) {
            return current
        }
        val result = current.toMutableList()
        other.forEach { type ->
            if (result.find { it.name() == type.name() } == null) result.add(type)
        }
        return result
    }


    private fun <T : NodeDirectivesBuilder> T.mergeDirectives(
        node: DirectivesContainer<*>,
        other: DirectivesContainer<*>?
    ): T {
        if (other == null) {
            this.directives(node.directives)
        } else {
            val directives = node.directives.toMutableList()
            other.directives.forEach { directive -> directives.add(directive) }
            this.directives(directives)
        }
        return this
    }

    private fun <T : NodeBuilder> T.mergeNode(node: Node<*>, other: DirectivesContainer<*>?): T {
        this.sourceLocation(node.sourceLocation)
        this.comments(node.comments ?: other?.comments)
        this.ignoredChars(node.ignoredChars ?: other?.ignoredChars)
        this.additionalData((other?.additionalData ?: emptyMap()) + node.additionalData)
        return this
    }

}

