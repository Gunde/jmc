/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The contents of this file are subject to the terms of either the Universal Permissive License
 * v 1.0 as shown at http://oss.oracle.com/licenses/upl
 *
 * or the following license:
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openjdk.jmc.flightrecorder.rules.jdk.combine;

import static org.openjdk.jmc.common.unit.UnitLookup.EPOCH_NS;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER_UNITY;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.util.Pair;
import org.openjdk.jmc.flightrecorder.JfrAttributes;

/**
 * Toolkit for calculating combined span values. Creates spans which consist of at least a start
 * time, an end time and a value. Spans are then combined together (see {@link Combinable} and
 * {@link Combiner}) to form the largest time span where the combined value is still deemed to be
 * over some threshold. Threshold can be either a defined limit or a calculated density.
 * <p>
 * Currently, it's up to the caller of this toolkit to call the methods with a subset of items that
 * have only one item iterable, which means that events should not overlap in time.
 */
public class SpanToolkit {
	// FIXME: Consider letting the spans handle quantities, but will increase allocation?

	/**
	 * Calculates the largest count cluster.
	 *
	 * @param items
	 *            the item collection
	 * @param countAttribute
	 *            the attribute to get count value from, is assumed to be an accumulative value, for
	 *            example exception statistics
	 * @param timestampAttribute
	 *            the attribute to get a time stamp from
	 * @return The maximum count span
	 */
	public static SpanSquare getMaxCountCluster(
		IItemCollection items, IAttribute<IQuantity> countAttribute, IAttribute<IQuantity> timestampAttribute) {

		Iterator<? extends IItemIterable> iiIterator = items.iterator();
		if (!iiIterator.hasNext()) {
			return null;
		}
		List<Pair<IQuantity, IQuantity>> countsByTime = new ArrayList<>();
		for (IItemIterable itemIterable : items) {
			IType<IItem> type = itemIterable.getType();
			IMemberAccessor<IQuantity, IItem> countAccessor = countAttribute.getAccessor(type);
			IMemberAccessor<IQuantity, IItem> timeAccessor = timestampAttribute.getAccessor(type);

			for (IItem item : itemIterable) {
				IQuantity count = countAccessor.getMember(item);
				IQuantity time = timeAccessor.getMember(item);
				countsByTime.add(new Pair<>(time, count));
			}
		}
		countsByTime.sort((p1, p2) -> p1.left.compareTo(p2.left));
		if (countsByTime.size() <= 1) {
			return null;
		}
		Pair<IQuantity, IQuantity> first = countsByTime.get(0);
		long lastCount = first.right.clampedLongValueIn(NUMBER_UNITY);
		long lastTimestamp = first.left.clampedLongValueIn(EPOCH_NS);
		List<SpanSquare> spans = new ArrayList<>();
		for (int i = 1; i < countsByTime.size(); i++) {
			long count = countsByTime.get(i).right.clampedLongValueIn(NUMBER_UNITY);
			long timestamp = countsByTime.get(i).left.clampedLongValueIn(EPOCH_NS);
			spans.add(new SpanSquare(lastTimestamp, timestamp, count - lastCount));
			lastCount = count;
			lastTimestamp = timestamp;
		}
		return SpanSquare.getMax(spans.toArray(new SpanSquare[spans.size()]));
	}

	/**
	 * Calculates the largest duration cluster.
	 *
	 * @param items
	 *            the item collection
	 * @return The maximum duration span
	 */
	public static SpanSquare getMaxDurationCluster(IItemCollection items) {
		if (!items.hasItems()) {
			return null;
		}
		List<SpanSquare> span = new ArrayList<>();
		List<Pair<IQuantity, IQuantity>> times = new ArrayList<>();
		for (IItemIterable itemIterable : items) {
			IMemberAccessor<IQuantity, IItem> startTime = JfrAttributes.START_TIME.getAccessor(itemIterable.getType());
			IMemberAccessor<IQuantity, IItem> endTime = JfrAttributes.END_TIME.getAccessor(itemIterable.getType());
			for (IItem item : itemIterable) {
				IQuantity st = startTime.getMember(item);
				IQuantity et = endTime.getMember(item);
				times.add(new Pair<>(st, et));
			}
		}
		times.sort((o1, o2) -> o1.left.compareTo(o2.left));
		for (Pair<IQuantity, IQuantity> time : times) {
			span.add(new SpanSquare(time.left.clampedLongValueIn(EPOCH_NS), time.right.clampedLongValueIn(EPOCH_NS)));
		}
		return SpanSquare.getMax(span.toArray(new SpanSquare[span.size()]));
	}

	/**
	 * Calculates the longest span where the combined value still is above the limit.
	 *
	 * @param items
	 *            the item collection
	 * @param valueAttribute
	 *            the value attribute
	 * @param endTimeAttribute
	 *            the end time attribute
	 * @param limit
	 *            the min limit
	 * @return a span
	 */
	public static SpanLimit getMaxSpanLimit(
		IItemCollection items, IAttribute<IQuantity> valueAttribute, IAttribute<IQuantity> endTimeAttribute,
		double limit) {
		List<Pair<IQuantity, IQuantity>> valuesByTime = new ArrayList<>();
		for (IItemIterable itemIterable : items) {
			IType<IItem> type = itemIterable.getType();
			IMemberAccessor<IQuantity, IItem> valueAccessor = valueAttribute.getAccessor(type);
			IMemberAccessor<IQuantity, IItem> timeAccessor = endTimeAttribute.getAccessor(type);

			for (IItem item : itemIterable) {
				IQuantity value = valueAccessor.getMember(item);
				IQuantity time = timeAccessor.getMember(item);
				valuesByTime.add(new Pair<>(time, value));
			}
		}
		valuesByTime.sort((p1, p2) -> p1.left.compareTo(p2.left));
		if (valuesByTime.size() <= 1) {
			return null;
		}
		Pair<IQuantity, IQuantity> first = valuesByTime.get(0);
		double lastValue = first.right.doubleValue();
		long lastTimestamp = first.left.clampedLongValueIn(EPOCH_NS);
		List<SpanLimit> periods = new ArrayList<>();
		for (int i = 1; i < valuesByTime.size(); i++) {
			Pair<IQuantity, IQuantity> values = valuesByTime.get(i);
			double value = values.right.doubleValue();
			long time = values.left.clampedLongValueIn(EPOCH_NS);
			periods.add(new SpanLimit(lastTimestamp, time, (value + lastValue) / 2, limit));
			lastValue = value;
			lastTimestamp = time;
		}
		return SpanLimit.getMaxSpan(periods.toArray(new SpanLimit[0]));
	}
}
