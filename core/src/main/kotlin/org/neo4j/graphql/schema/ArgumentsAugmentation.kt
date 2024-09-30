package org.neo4j.graphql.schema

import graphql.language.InputValueDefinition

interface ArgumentsAugmentation : AugmentationBase {
    fun augmentArguments(args: MutableList<InputValueDefinition>)

    fun getAugmentedArguments(): List<InputValueDefinition> {
        val args = mutableListOf<InputValueDefinition>()
        augmentArguments(args)
        return args
    }
}
