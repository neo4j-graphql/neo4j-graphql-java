package org.neo4j.graphql.schema.relations

import graphql.language.ListType
import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.create.CreateFieldInput
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.domain.inputs.update.UpdateFieldInput
import org.neo4j.graphql.isList
import org.neo4j.graphql.schema.BaseAugmentationV2

/**
 * Base class to augment a relation field
 */
interface RelationFieldBaseAugmentation {

    fun generateFieldCreateIT(): String?

    fun generateFieldUpdateIT(): String?

    fun generateFieldConnectOrCreateIT(): String?

    fun generateFieldConnectIT(): String?

    fun generateFieldDisconnectIT(): String?

    fun generateFieldDeleteIT(): String?

    fun generateFieldRelationCreateIT(): String?

    fun generateFieldWhereIT(): String?

    fun generateFieldConnectionWhereIT(): String?
}
