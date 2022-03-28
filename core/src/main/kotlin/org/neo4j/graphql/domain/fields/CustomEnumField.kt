package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.domain.TypeMeta

class CustomEnumField(
    fieldName: String,
    typeMeta: TypeMeta,
) : ScalarField(
    fieldName,
    typeMeta,
), HasDefaultValue {
    override var defaultValue: Value<*>? = null
}
