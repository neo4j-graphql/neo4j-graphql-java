package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.naming.InterfaceEntityOperations

class Interface(
    name: String,
    description: Description? = null,
    comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    interfaces: List<Interface>,
    annotations: Annotations,
) : ImplementingType(name, description, comments, fields, interfaces, annotations), NodeResolver {

    var implementations: Map<String, Node> = emptyMap()

    override val operations = InterfaceEntityOperations(name, annotations)

    fun getRequiredImplementation(name: String) = implementations[name]
        ?: throw IllegalArgumentException("unknown implementation $name for interface ${this.name}")

    override fun getRequiredNode(name: String) = implementations[name]
        ?: throw IllegalArgumentException("unknown implementation $name for interface ${this.name}")

    override fun getNode(name: String) = implementations[name]

    override fun toString(): String {
        return "Interface('$name')"
    }
}
