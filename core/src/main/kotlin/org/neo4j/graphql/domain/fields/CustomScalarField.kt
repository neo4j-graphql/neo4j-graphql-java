package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.FieldAnnotations

class CustomScalarField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    type,
    annotations,
    schemaConfig,
)
