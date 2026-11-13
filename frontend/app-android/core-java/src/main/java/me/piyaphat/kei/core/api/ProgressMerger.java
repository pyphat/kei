package me.piyaphat.kei.core.api;

import java.util.List;

public interface ProgressMerger {
    Progress merge(Progress local, Progress remote);
    boolean shouldOfferJump(Progress mine, Progress remote, List<Long> chapterOrder);
}
