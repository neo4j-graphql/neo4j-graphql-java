package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.TimestampDirective

class TemporalField<OWNER: Any>(fieldName: String, typeMeta: TypeMeta) :
    PrimitiveField<OWNER>(fieldName, typeMeta),
    ConstrainableField
{
    // TODO rename field to `generateTimestampOperations`
    var timestamps: List<TimestampDirective.TimeStampOperation>? = null

    override val generated: Boolean get()  = super.generated || timestamps != null
}
