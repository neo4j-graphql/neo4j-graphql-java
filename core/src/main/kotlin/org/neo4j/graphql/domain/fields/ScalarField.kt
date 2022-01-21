package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

abstract class ScalarField<OWNER: Any>(fieldName: String, typeMeta: TypeMeta) : BaseField<OWNER>(fieldName, typeMeta)
