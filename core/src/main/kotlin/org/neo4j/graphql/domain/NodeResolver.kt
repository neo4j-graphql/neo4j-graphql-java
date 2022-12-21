package org.neo4j.graphql.domain

interface NodeResolver {

    fun getRequiredNode(name: String): Node

    fun getNode(name: String): Node?

}
