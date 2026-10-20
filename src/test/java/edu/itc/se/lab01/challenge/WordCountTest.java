package edu.itc.se.lab01.challenge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

public class WordCountTest {

    @Test
    void testParallelMatchesSingleThreadedReference(@TempDir Path tempDir) throws Exception {
        // Generate 20 test files with 1,000 words each
        WordCount.generateTestData(tempDir, 20, 1000);

        // Run single-threaded reference
        Map<String, Long> expected = WordCount.countSingleThreaded(tempDir);

        // Run parallel WordCount with 4 workers
        WordCount wc = new WordCount(tempDir, 4, false, 10, 0);
        wc.run();

        Map<String, Long> actual = new HashMap<>();
        wc.getCounts().forEach((k, v) -> actual.put(k, v.sum()));

        // Assert both produce identical maps
        assertEquals(expected.size(), actual.size(), "Word count dictionary size mismatch");
        assertEquals(expected, actual, "Word frequency maps must match exactly");
    }

    @Test
    void testTimeoutStopsWithinDeadline(@TempDir Path tempDir) throws Exception {
        // Generate 5,000 files with 500 words each (large workload)
        WordCount.generateTestData(tempDir, 5000, 500);

        long start = System.currentTimeMillis();

        // Run WordCount with 1 second timeout
        WordCount wc = new WordCount(tempDir, 2, false, 5, 1);
        wc.run();

        long duration = System.currentTimeMillis() - start;

        // Requirement: stops within 3 seconds when given --timeout 1
        assertTrue(duration < 3000, "Tool must stop within 3 seconds on timeout, but took " + duration + " ms");
    }
}
