package me.piyaphat.kei.core.impl;

import me.piyaphat.kei.core.api.UploadBatchPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

public final class SizeAndCountBatchPlanner implements UploadBatchPlanner {
    @Override
    public <T> List<List<T>> plan(List<T> items, ToLongFunction<T> size,
                                  int maxCount, long maxBytes) {
        if (maxCount < 1 || maxBytes < 1) throw new IllegalArgumentException();
        List<List<T>> batches = new ArrayList<>();
        List<T> batch = new ArrayList<>();
        long bytes = 0;
        for (T item : items) {
            long itemSize = size.applyAsLong(item);
            if (itemSize > maxBytes) {
                if (!batch.isEmpty()) batches.add(batch);
                batch = new ArrayList<>();
                batches.add(List.of(item));
                bytes = 0;
                continue;
            }
            if (!batch.isEmpty()
                    && (batch.size() == maxCount || itemSize > maxBytes - bytes)) {
                batches.add(batch);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(item);
            bytes += itemSize;
        }
        if (!batch.isEmpty()) batches.add(batch);
        return batches;
    }
}
