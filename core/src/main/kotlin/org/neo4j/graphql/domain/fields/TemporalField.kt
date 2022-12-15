package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.TimestampDirective

class TemporalField(fieldName: String, typeMeta: TypeMeta, schemaConfig: SchemaConfig) :
    PrimitiveField(fieldName, typeMeta, schemaConfig),
    ConstrainableField, AuthableField, MutableField
{
    // TODO rename field to `generateTimestampOperations`
    var timestamps: Set<TimestampDirective.TimeStampOperation>? = null

    override val generated: Boolean get()  = super.generated || timestamps != null
}
