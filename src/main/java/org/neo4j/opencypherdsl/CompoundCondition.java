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
import org.neo4j.opencypherdsl.support.Visitable;
import org.neo4j.opencypherdsl.support.Visitor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.apiguardian.api.API.Status.INTERNAL;
import static org.neo4j.opencypherdsl.Operator.*;

/**
 * A condition that consists of one or two {@link Condition conditions} connected by a
 * <a href="https://en.wikipedia.org/wiki/Logical_connective">Logical connective (operator)</a>.
 *
 * @author Michael J. Simons
 * @since 1.0
 */
@API(status = INTERNAL, since = "1.0")
public final class CompoundCondition implements Condition {

	/**
	 * The empty, compound condition.
	 */
	static final CompoundCondition EMPTY_CONDITION = new CompoundCondition(null);
	static final EnumSet<Operator> VALID_OPERATORS = EnumSet.of(AND, OR, XOR);

	static CompoundCondition create(Condition left, Operator operator, Condition right) {

		Assert.isTrue(VALID_OPERATORS.contains(operator),
			"Operator " + operator + " is not a valid operator for a compound condition.");

		Assert.notNull(left, "Left hand side condition is required.");
		Assert.notNull(operator, "Operator is required.");
		Assert.notNull(right, "Right hand side condition is required.");
		return new CompoundCondition(operator)
			.add(operator, left)
			.add(operator, right);
	}

	static CompoundCondition empty() {

		return EMPTY_CONDITION;
	}

	private final Operator operator;

	private final List<Condition> conditions;

	private CompoundCondition(Operator operator) {
		this.operator = operator;
		this.conditions = new ArrayList<>();
	}

	@Override
	public Condition and(Condition condition) {
		return this.add(AND, condition);
	}

	@Override
	public Condition or(Condition condition) {
		return this.add(OR, condition);
	}

	@Override
	public Condition xor(Condition condition) {
		return this.add(XOR, condition);
	}

	private CompoundCondition add(
		Operator chainingOperator,
		Condition condition
	) {
		if (this == EMPTY_CONDITION) {
			return new CompoundCondition(chainingOperator).add(chainingOperator, condition);
		}

		if (condition == EMPTY_CONDITION) {
			return this;
		}

		if (condition instanceof CompoundCondition) {
			CompoundCondition compoundCondition = (CompoundCondition) condition;
			if (this.operator == chainingOperator && chainingOperator == compoundCondition.operator) {
				if (compoundCondition.canBeFlattenedWith(chainingOperator)) {
					this.conditions.addAll(compoundCondition.conditions);
				} else {
					this.conditions.add(compoundCondition);
				}
			} else {
				CompoundCondition inner = new CompoundCondition(chainingOperator);
				inner.conditions.add(compoundCondition);
				this.conditions.add(inner);
			}

			return this;
		}

		if (this.operator == chainingOperator) {
			conditions.add(condition);
			return this;
		}

		return CompoundCondition.create(this, chainingOperator, condition);
	}

	/**
	 * @param operatorBefore The operator that is to be used before this condition
	 * @return True if all conditions in this condition are either simple or compound annotation with the same boolean operator as {@code operatorBefore}
	 */
	private boolean canBeFlattenedWith(Operator operatorBefore) {

		for (Condition c : this.conditions) {
			if (c instanceof CompoundCondition && ((CompoundCondition) c).operator != operatorBefore) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void accept(Visitor visitor) {

		// There is nothing to visit here
		if (this.conditions.isEmpty()) {
			return;
		}

		// Fold single condition
		boolean hasManyConditions = this.conditions.size() > 1;
		if (hasManyConditions) {
			visitor.enter(this);
		}

		// The first nested condition does not need an operator
		acceptVisitorWithOperatorForChildCondition(visitor, null, conditions.get(0));

		// All others do
		if (hasManyConditions) {
			for (Condition condition : conditions.subList(1, conditions.size())) {
				// This takes care of a potential inner compound condition that got added with a different operator
				// and thus forms a tree.
				Operator actualOperator = condition instanceof CompoundCondition ?
					((CompoundCondition) condition).operator :
					operator;
				acceptVisitorWithOperatorForChildCondition(visitor, actualOperator, condition);
			}
			visitor.leave(this);
		}
	}

	private static void acceptVisitorWithOperatorForChildCondition(
		Visitor visitor, Operator operator, Condition condition
	) {
		Visitable.visitIfNotNull(operator, visitor);
		condition.accept(visitor);
	}
}
