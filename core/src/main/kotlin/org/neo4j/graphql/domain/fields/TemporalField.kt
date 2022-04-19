package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.TimestampDirective

class TemporalField(fieldName: String, typeMeta: TypeMeta) :
    PrimitiveField(fieldName, typeMeta),
    ConstrainableField, AuthableField, MutableField
{
    // TODO rename field to `generateTimestampOperations`
    var timestamps: Set<TimestampDirective.TimeStampOperation>? = null

    override val generated: Boolean get()  = super.generated || timestamps != null
}
