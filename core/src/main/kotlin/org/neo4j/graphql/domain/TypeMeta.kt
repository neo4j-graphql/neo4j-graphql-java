package org.neo4j.graphql.domain

import graphql.language.*
import org.neo4j.graphql.Constants.CARTESIAN_POINT_INPUT_TYPE
import org.neo4j.graphql.Constants.CARTESIAN_POINT_TYPE
import org.neo4j.graphql.Constants.POINT_INPUT_TYPE
import org.neo4j.graphql.Constants.POINT_TYPE
import org.neo4j.graphql.isList
import org.neo4j.graphql.isListElementRequired
import org.neo4j.graphql.name

/**
 * Metadata about a fields type on either
 * FieldDefinitionNode or InputValueDefinitionNode.
 */
class TypeMeta(
    val type: Type<*>,
    val whereType: Type<*>,
    val createType: Type<*>? = null,
    val updateType: Type<*>? = null,
) {

    companion object {
        fun create(field: FieldDefinition): TypeMeta {
            var typeName = field.type.name()
            when (typeName) {
                POINT_TYPE -> typeName = POINT_INPUT_TYPE
                CARTESIAN_POINT_TYPE -> typeName = CARTESIAN_POINT_INPUT_TYPE
            }
            var type: Type<*> = TypeName(typeName)
            if (field.type.isList()) {
                if (field.type.isListElementRequired()) {
                    type = NonNullType(type)
                }
                type = ListType(type)
            }

            return TypeMeta(
                type = field.type,
                whereType = type,
                createType = if (field.type is NonNullType) NonNullType(type) else type,
                updateType = type,
            )
        }
    }
}
