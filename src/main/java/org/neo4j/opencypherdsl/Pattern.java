/*
 * Copyright (c) 2019-2020 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.opencypherdsl;

import org.apiguardian.api.API;
import org.neo4j.opencypherdsl.support.TypedSubtree;

import java.util.List;

import static org.apiguardian.api.API.Status.INTERNAL;

/**
 * A pattern is something that can be matched. It consists of one or more pattern elements. Those can be nodes or chains
 * of nodes and relationships.
 * <p>
 * See <a href="https://s3.amazonaws.com/artifacts.opencypher.org/railroad/Pattern.html">Pattern</a>.
 *
 * @author Michael J. Simons
 * @param <S> The pattern's entry type
 * @since 1.0
 */
@API(status = INTERNAL, since = "1.0")
public final class Pattern<S extends Pattern<S>> extends TypedSubtree<PatternElement, S> {

	Pattern(List<PatternElement> patternElements) {
		super(patternElements);
	}
}
