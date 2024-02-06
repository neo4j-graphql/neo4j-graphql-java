package org.neo4j.graphql.domain.fields

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.name

class TemporalField(fieldName: String, typeMeta: TypeMeta, annotations: FieldAnnotations, schemaConfig: SchemaConfig) :
    PrimitiveField(fieldName, typeMeta, annotations, schemaConfig),
    ConstrainableField, AuthableField, MutableField {
    // TODO rename field to `generateTimestampOperations`
    val timestamps get() = annotations.timestamp?.operations

    // TODO harmonize
    override val generated: Boolean get() = super.generated || timestamps != null

    override fun convertInputToCypher(input: Expression): Expression {
        if (Constants.JS_COMPATIBILITY) {
            return super.convertInputToCypher(input)
        }
        return when (typeMeta.type.name()) {
            Constants.DATE_TIME -> Cypher.datetime(input)
            Constants.LOCAL_DATE_TIME -> Cypher.localdatetime(input)
            Constants.LOCAL_TIME -> Cypher.localtime(input)
            Constants.DATE -> Cypher.date(input)
            Constants.TIME -> Cypher.time(input)
            else -> super.convertInputToCypher(input)
        }
    }
}
