package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

abstract class ScalarField(fieldName: String, typeMeta: TypeMeta) : BaseField(fieldName, typeMeta), AuthableField, MutableField
