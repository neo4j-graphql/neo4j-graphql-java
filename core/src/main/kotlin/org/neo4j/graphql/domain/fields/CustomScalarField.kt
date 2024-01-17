package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations

class CustomScalarField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    annotations,
    schemaConfig,
)
