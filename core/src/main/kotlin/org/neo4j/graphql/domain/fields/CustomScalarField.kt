package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class CustomScalarField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    annotations,
    schemaConfig,
)
