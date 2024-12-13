package org.neo4j.graphql.schema

import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.Type
import kotlin.reflect.KProperty1

interface AugmentationBase {

    fun inputValue(
        name: String,
        type: Type<*>,
        init: (InputValueDefinition.Builder.() -> Unit)? = null
    ): InputValueDefinition {
        val input = InputValueDefinition.newInputValueDefinition()
        input.name(name)
        input.type(type)
        init?.let { input.it() }
        return input.build()
    }

    fun field(
        name: KProperty1<*, *>,
        type: Type<*>,
        args: List<InputValueDefinition>? = emptyList(),
        init: (FieldDefinition.Builder.() -> Unit)? = null
    ): FieldDefinition = field(name.name, type, args, init)

    fun field(
        name: String,
        type: Type<*>,
        args: List<InputValueDefinition>? = emptyList(),
        init: (FieldDefinition.Builder.() -> Unit)? = null
    ): FieldDefinition {
        val field = FieldDefinition.newFieldDefinition()
        field.name(name)
        field.type(type)
        if (args != null) {
            field.inputValueDefinitions(args)
        }
        init?.let { field.it() }
        return field.build()
    }

}
