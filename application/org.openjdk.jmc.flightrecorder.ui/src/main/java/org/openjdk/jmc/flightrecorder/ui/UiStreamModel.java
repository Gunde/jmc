package org.openjdk.jmc.flightrecorder.ui;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.flightrecorder.ItemCollectionToolkit;
import org.openjdk.jmc.flightrecorder.StreamModel;
import org.openjdk.jmc.flightrecorder.internal.EventArray;
import org.openjdk.jmc.flightrecorder.ui.EventTypeFolderNode.TypeWithCategory;

public class UiStreamModel extends StreamModel {

	public UiStreamModel(EventArray[] eventsByType) {
		super(eventsByType);
	}

	public EventTypeFolderNode getTypeTree(Stream<IItemIterable> items) {
		Map<IType<IItem>, Long> itemCountByType = items
				.collect(Collectors.toMap(IItemIterable::getType, is -> is.getItemCount(), Long::sum));
		Function<EventArray, TypeWithCategory> eventArrayToTypeWithCategoryMapper = ea -> {
			Long count = itemCountByType.remove(ea.getType());
			return count == null ? null : new TypeWithCategory(ea.getType(), ea.getTypeCategory(), count);
		};
		return EventTypeFolderNode
				.buildRoot(Stream.of(eventsByType).map(eventArrayToTypeWithCategoryMapper).filter(Objects::nonNull));
	}

	public EventTypeFolderNode getTypeTree() {
		return getTypeTree(ItemCollectionToolkit.stream(getItems()));
	}

}
