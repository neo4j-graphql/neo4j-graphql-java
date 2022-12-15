package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

class CustomScalarField(
    fieldName: String,
    typeMeta: TypeMeta,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    schemaConfig,
) {
    override val predicates: Map<String, ScalarPredicateDefinition> = initPredicates()

    private fun initPredicates(): Map<String, ScalarPredicateDefinition> {
        return mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL)
    }
}
