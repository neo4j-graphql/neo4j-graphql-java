package org.neo4j.opencypherdsl;

import org.apiguardian.api.API;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

/**
 * Exposes an properties.
 *
 * @author Andreas Berger
 * @since 1.0
 */
@API(status = EXPERIMENTAL, since = "1.0")
public interface PropertyContainer<T> extends Named {
    /**
     * Creates a a copy of this property container with additional properties.
     * Creates a property container without properties when no properties are passed to this method.
     *
     * @param newProperties the new properties (can be {@literal null} to remove exiting properties).
     * @return The new property container.
     */
    T properties(MapExpression<?> newProperties);

    /**
     * Creates a a copy of this property container with additional properties.
     * Creates a property container without properties when no properties are passed to this method.
     *
     * @param keysAndValues A list of key and values. Must be an even number, with alternating {@link String} and {@link Expression}.
     * @return The new property container.
     */
    T properties(Object... keysAndValues);

    /**
     * Creates a new {@link Property} associated with this property container.
     * <p>
     * Note: The property container does not track property creation and there is no possibility to enumerate all
     * properties that have been created for this property container.
     *
     * @param name property name, must not be {@literal null} or empty.
     * @return a new {@link Property} associated with this {@link Relationship}.
     * @since 1.0.1
     */
    Property property(String name);
}
