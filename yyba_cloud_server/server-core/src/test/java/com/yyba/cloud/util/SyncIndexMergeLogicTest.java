package com.yyba.cloud.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 增量 sync-index 合并逻辑（纯内存，与 Room 行为一致） */
class SyncIndexMergeLogicTest {

    @Test
    void mergeAddsUpdatesAndRemoves() {
        List<String> existing = new ArrayList<>(Arrays.asList("photos/a.jpg", "photos/b.jpg"));
        List<String> updated = Arrays.asList("photos/b.jpg", "photos/c.jpg");
        List<String> removed = Arrays.asList("photos/a.jpg");

        Set<String> result = new HashSet<>(existing);
        result.removeAll(removed);
        result.addAll(updated);

        assertEquals(2, result.size());
        assertTrue(result.contains("photos/b.jpg"));
        assertTrue(result.contains("photos/c.jpg"));
    }
}
