package org.neo4j.graphql.handler

import org.neo4j.graphql.SchemaConfig

/**
 * This is a base class for all Node or Relation related data fetcher which allows batch input.
 */
// TODO remove?
abstract class BaseDataFetcherForContainerBatch(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {
}
