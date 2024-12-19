package org.neo4j.graphql.utils

import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.Options

object CypherUtils {

    private val NORMALIZING_PARSE_OPTIONS = Options.newOptions()
        .createSortedMaps(true)
        .alwaysCreateRelationshipsLTR(true)
        .build()

    private val NORMALIZING_RENDER_OPTIONS = Configuration.newConfig()
        .withPrettyPrint(true)
        .withGeneratedNames(
            setOf(
                Configuration.GeneratedNames.ALL_ALIASES,
                Configuration.GeneratedNames.PARAMETER_NAMES,
                Configuration.GeneratedNames.ENTITY_NAMES,
            )
        )
        .build()

    fun prettyPrintCypher(cypher: String, dialect: Dialect = Dialect.NEO4J_5): String {
        val statement = CypherParser.parse(cypher, Options.defaultOptions())
        return Renderer.getRenderer(
            Configuration
                .newConfig()
                .withDialect(dialect)
                .withIndentStyle(Configuration.IndentStyle.TAB)
                .withPrettyPrint(true)
                .build()
        ).render(statement)
    }

    fun normalizeCypher(cypher: String): String {
        val renderer = Renderer.getRenderer(NORMALIZING_RENDER_OPTIONS)
        return renderer.render(parseNormalized(cypher))
    }

    fun parseNormalized(cypher: String): Statement =
        CypherParser.parse(cypher, NORMALIZING_PARSE_OPTIONS)
}
