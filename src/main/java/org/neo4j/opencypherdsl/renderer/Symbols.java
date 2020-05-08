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
package org.neo4j.opencypherdsl.renderer;

/**
 * Symbols used during Cypher rendering.
 *
 * @author Michael J. Simons
 * @since 1.0
 */
final class Symbols {

	public static final String NODE_LABEL_START = ":";
	public static final String REL_TYPE_START = ":";
	public static final String REL_TYP_SEPARATOR = "|";

	private Symbols() {
	}
}
