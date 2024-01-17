package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.name

class TemporalField(fieldName: String, typeMeta: TypeMeta, annotations: Annotations, schemaConfig: SchemaConfig) :
    PrimitiveField(fieldName, typeMeta, annotations, schemaConfig),
    ConstrainableField, AuthableField, MutableField {
    // TODO rename field to `generateTimestampOperations`
    val timestamps get() = annotations.timestamp?.operations

    // TODO harmonize
    override val generated: Boolean get() = super.generated || timestamps != null

    override fun convertInputToCypher(input: Expression): Expression = when (typeMeta.type.name()) {
        Constants.DATE_TIME -> Functions.datetime(input)
        Constants.LOCAL_DATE_TIME -> Functions.localdatetime(input)
        Constants.LOCAL_TIME -> Functions.localtime(input)
        Constants.DATE -> Functions.date(input)
        Constants.TIME -> Functions.time(input)
        else -> super.convertInputToCypher(input)
    }
}
