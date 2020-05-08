package org.neo4j.opencypherdsl;

import kotlin.Deprecated;

import java.util.Objects;

@Deprecated(message = "This is a shortcut to use the old generator logic in combination with this DSL, remove as soon DSL is used everywhere")
public class PassThrough extends Literal<String> {

    public PassThrough(String content) {
        super(content);
    }

    @Override
    public String asString() {
        return Objects.requireNonNull(getContent());
    }
}
