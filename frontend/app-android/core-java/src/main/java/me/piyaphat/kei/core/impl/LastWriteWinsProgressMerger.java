package me.piyaphat.kei.core.impl;

import me.piyaphat.kei.core.api.Progress;
import me.piyaphat.kei.core.api.ProgressMerger;

import java.util.List;
import java.util.Objects;

public final class LastWriteWinsProgressMerger implements ProgressMerger {
    @Override
    public Progress merge(Progress local, Progress remote) {
        if (local == null) return remote;
        if (remote == null) return local;
        return remote.ts() >= local.ts() ? remote : local;
    }

    @Override
    public boolean shouldOfferJump(Progress mine, Progress remote, List<Long> chapterOrder) {
        if (remote == null || chapterOrder.isEmpty()) return false;
        int remoteChapter = chapterOrder.indexOf(remote.chapterId());
        if (remoteChapter < 0) return false;
        if (mine == null) return remoteChapter > 0 || remote.page() > 0;
        if (Objects.equals(mine.deviceId(), remote.deviceId()) || remote.ts() <= mine.ts()) {
            return false;
        }
        int mineChapter = chapterOrder.indexOf(mine.chapterId());
        return mineChapter >= 0 && (remoteChapter > mineChapter
                || remoteChapter == mineChapter && remote.page() > mine.page());
    }
}
