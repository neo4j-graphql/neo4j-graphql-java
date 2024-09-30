package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.name

class TemporalField(fieldName: String, type: Type<*>, annotations: FieldAnnotations, schemaConfig: SchemaConfig) :
    PrimitiveField(fieldName, type, annotations, schemaConfig) {

    override fun convertInputToCypher(input: Expression): Expression {
        if (Constants.JS_COMPATIBILITY) {
            return super.convertInputToCypher(input)
        }
        return when (type.name()) {
            Constants.DATE_TIME -> Cypher.datetime(input)
            Constants.LOCAL_DATE_TIME -> Cypher.localdatetime(input)
            Constants.LOCAL_TIME -> Cypher.localtime(input)
            Constants.DATE -> Cypher.date(input)
            Constants.TIME -> Cypher.time(input)
            else -> super.convertInputToCypher(input)
        }
    }
}
