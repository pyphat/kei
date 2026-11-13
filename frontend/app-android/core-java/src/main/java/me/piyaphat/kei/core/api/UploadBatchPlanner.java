package me.piyaphat.kei.core.api;

import java.util.List;
import java.util.function.ToLongFunction;

public interface UploadBatchPlanner {
    <T> List<List<T>> plan(List<T> items, ToLongFunction<T> size,
                           int maxCount, long maxBytes);
}
