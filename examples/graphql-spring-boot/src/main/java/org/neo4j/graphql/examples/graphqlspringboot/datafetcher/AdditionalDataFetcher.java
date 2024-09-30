package org.neo4j.graphql.examples.graphqlspringboot.datafetcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
class AdditionalDataFetcher {

    @SchemaMapping(typeName = "Movie", field = "bar")
    public String bar() {
        return "foo";
    }

    @SchemaMapping(typeName = "Movie", field = "javaData")
    public List<JavaData> javaData(DataFetchingEnvironment env) {
        //noinspection unchecked
        Object title = ((Map<String, Object>) Objects.requireNonNull(env.getSource())).get("title");
        return Collections.singletonList(new JavaData("test " + title));
    }

    @QueryMapping
    public String other() {
        return "other";
    }

    public static class JavaData {
        @JsonProperty("name")
        public String name;

        public JavaData(String name) {
            this.name = name;
        }
    }
}
