package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField

//TODO completed
class CreateConnectionWhere(val schemaConfig: SchemaConfig, val queryContext: QueryContext?) {

    fun createConnectionWhere(
        whereInputAny: Any?,
        node: Node,
        nodeVariable: PropertyContainer,
        relationship: RelationField,
        relationshipVariable: PropertyContainer,
        parameterPrefix: String
    ): Condition? {

        val whereInput = whereInputAny as? Map<*, *> ?: return null

        var result: Condition? = null

        whereInput.forEach { (k, value) ->
            val key = k as? String ?: throw IllegalArgumentException("expected a string")

            if (Constants.PREDICATE_JOINS.contains(key)) {
                var innerCondition: Condition? = null
                (value as List<*>).forEachIndexed { index, v ->
                    createConnectionWhere(
                        v,
                        node,
                        nodeVariable,
                        relationship,
                        relationshipVariable,
                        schemaConfig.namingStrategy.resolveName(parameterPrefix, key, index)
                    )?.let {

                        innerCondition = if (Constants.OR == key) {
                            innerCondition or it
                        } else {
                            innerCondition and it
                        }
                    }
                }
                innerCondition?.let { result = result and it }
            }

            if (key.startsWith(Constants.EDGE_FIELD) && relationship.properties != null) {
                CreateWhere(schemaConfig, queryContext)
                    .createWhere(
                        relationship.properties,
                        value,
                        relationshipVariable,
                        schemaConfig.namingStrategy.resolveName(parameterPrefix, k)
                    )
                    ?.let {
                        // TODO check `not` case to work correctly
                        result = result and it
                    }


            }

            if (key.startsWith(Constants.NODE_FIELD) || key == node.name) {
                val valueMap = value as? Map<*, *>
                if (valueMap?.size == 1
                    && valueMap[Constants.ON] != null
                    && (valueMap[Constants.ON] as? Map<*, *>)?.containsKey(node.name) == true
                ) {
                    throw IllegalArgumentException("_on is used as the only argument and node is not present within")
                }

                val inputOnForNode = (valueMap?.get(Constants.ON) as? Map<*, *>)?.get(node.name) as? Map<*, *>
                val inputExcludingOnForNode = valueMap?.toMutableMap()
                    ?.apply { keys.removeAll(inputOnForNode?.keys ?: emptySet()) }


                CreateWhere(schemaConfig, queryContext)
                    .createWhere(
                        node,
                        inputExcludingOnForNode,
                        nodeVariable,
                        schemaConfig.namingStrategy.resolveName(parameterPrefix, k)
                    )
                    ?.let {
                        // TODO check `not` case to work correctly
                        result = result and it
                    }

                val inputOnForNodeExcludingRoot = inputOnForNode
                    ?.toMutableMap()
                    ?.apply { keys.removeAll(inputExcludingOnForNode?.keys ?: emptySet()) }
                if (inputOnForNode != null) {
                    CreateWhere(schemaConfig, queryContext)
                        .createWhere(
                            node,
                            inputOnForNodeExcludingRoot,
                            nodeVariable,
                            schemaConfig.namingStrategy.resolveName(parameterPrefix, k, "on", node.name)
                        )
                        ?.let {
                            // TODO check `not` case to work correctly
                            result = result and it
                        }
                }

            }
        }

        return result
    }
}
