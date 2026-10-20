package edu.itc.se.lab01.challenge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WordCount {
    private static final Path POISON = Path.of("__POISON__");
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}]+");

    private final Path dir;
    private final int workers;
    private final boolean virtual;
    private final int top;
    private final long timeoutSec;

    private final BlockingQueue<Path> queue = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final LongAdder wordsDone = new LongAdder();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private Thread scannerThread;
    private ExecutorService workerPool;
    private ScheduledExecutorService reporterService;
    private ScheduledFuture<?> timeoutFuture;
    private Thread shutdownHook;
    private CountDownLatch workersLatch;

    private long startTimeMs;
    private int totalFilesCount = 0;

    public WordCount(Path dir, int workers, boolean virtual, int top, long timeoutSec) {
        this.dir = dir;
        this.workers = workers > 0 ? workers : Runtime.getRuntime().availableProcessors();
        this.virtual = virtual;
        this.top = top > 0 ? top : 10;
        this.timeoutSec = timeoutSec;
    }

    public ConcurrentHashMap<String, LongAdder> getCounts() {
        return counts;
    }

    public int getFilesDone() {
        return filesDone.get();
    }

    public long getWordsDone() {
        return wordsDone.sum();
    }

    public void run() throws InterruptedException {
        startTimeMs = System.currentTimeMillis();

        // Count total files first for progress reporting
        try (Stream<Path> s = Files.walk(dir)) {
            totalFilesCount = (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            System.err.println("Failed to read directory: " + e.getMessage());
            return;
        }

        workersLatch = new CountDownLatch(workers);

        // Executor service for workers
        workerPool = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(workers);

        // Scanner thread
        scannerThread = Thread.ofPlatform().name("scanner").start(this::scan);

        // Reporter thread every 500 ms
        reporterService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reporter");
            t.setDaemon(true);
            return t;
        });
        reporterService.scheduleAtFixedRate(this::report, 500, 500, TimeUnit.MILLISECONDS);

        // Shutdown hook
        shutdownHook = new Thread(() -> shutdown(true), "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Schedule timeout deadline if specified
        if (timeoutSec > 0) {
            timeoutFuture = reporterService.schedule(() -> {
                System.out.printf("[%4.1fs] deadline reached - stopping%n", (System.currentTimeMillis() - startTimeMs) / 1000.0);
                shutdown(true);
            }, timeoutSec, TimeUnit.SECONDS);
        }

        // Submit worker tasks
        for (int i = 0; i < workers; i++) {
            workerPool.submit(this::work);
        }

        // Wait for workers to finish normally
        workersLatch.await();

        // If stopped normally (not via cancellation)
        if (stopping.compareAndSet(false, true)) {
            cleanup();
            printResult(false);
        }
    }

    private void scan() {
        try (Stream<Path> paths = Files.walk(dir)) {
            Iterable<Path> iterable = () -> paths.filter(Files::isRegularFile).iterator();
            for (Path path : iterable) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                queue.put(path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("Scanner error: " + e.getMessage());
        } finally {
            // Signal workers to terminate
            for (int i = 0; i < workers; i++) {
                try {
                    queue.put(POISON);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void work() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Path path = queue.take();
                if (path.equals(POISON)) {
                    break;
                }
                countFile(path);
                filesDone.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workersLatch.countDown();
        }
    }

    private void countFile(Path path) {
        long fileWords = 0;
        try (Stream<String> lines = Files.lines(path)) {
            Iterable<String> it = lines::iterator;
            for (String line : it) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                String[] tokens = WORD_SPLIT.split(line);
                for (String token : tokens) {
                    if (!token.isEmpty()) {
                        String word = token.toLowerCase();
                        counts.computeIfAbsent(word, k -> new LongAdder()).increment();
                        fileWords++;
                    }
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable files
        }
        wordsDone.add(fileWords);
    }

    private void report() {
        if (stopping.get()) return;

        double elapsedSec = Math.max(0.1, (System.currentTimeMillis() - startTimeMs) / 1000.0);
        long currentWords = wordsDone.sum();
        long wordsPerSec = (long) (currentWords / elapsedSec);

        // Weakly consistent snapshot of top 3 words without locking
        List<Map.Entry<String, LongAdder>> top3 = counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(3)
                .toList();

        StringBuilder topStr = new StringBuilder();
        for (var e : top3) {
            if (topStr.length() > 0) topStr.append(" ");
            topStr.append(String.format("%s(%,d)", e.getKey(), e.getValue().sum()));
        }

        System.out.printf("[%4.1fs] files %d/%d  words %,d  %,d w/s  top: %s%n",
                elapsedSec, filesDone.get(), totalFilesCount, currentWords, wordsPerSec, topStr);
    }

    public void shutdown(boolean partial) {
        if (stopping.compareAndSet(false, true)) {
            cleanup();
            printResult(partial);
        }
    }

    private void cleanup() {
        if (scannerThread != null && scannerThread.isAlive()) {
            scannerThread.interrupt();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reporterService != null) {
            reporterService.shutdownNow();
        }
        if (timeoutFuture != null) {
            timeoutFuture.cancel(true);
        }
        try {
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        } catch (IllegalStateException ignored) {
            // Already shutting down
        }
    }

    public void printResult(boolean partial) {
        double elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        long totalWords = wordsDone.sum();
        int files = filesDone.get();
        String mode = virtual ? "virtual" : ("platform(" + workers + ")");

        if (partial) {
            System.out.printf("[PARTIAL] files=%d words=%,d elapsed=%.2fs mode=%s%n",
                    files, totalWords, elapsedSec, mode);
        } else {
            System.out.printf("DONE files=%d words=%,d elapsed=%.2fs mode=%s%n",
                    files, totalWords, elapsedSec, mode);
        }

        List<Map.Entry<String, Long>> sortedList = counts.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .toList();

        System.out.printf("%-6s %-15s %-12s %s%n", "rank", "word", "count", "share");
        int rank = 1;
        for (var entry : sortedList) {
            if (rank > top) break;
            double share = totalWords > 0 ? (entry.getValue() * 100.0 / totalWords) : 0.0;
            System.out.printf("%-6d %-15s %-12s %.2f %%%n",
                    rank, entry.getKey(), String.format("%,d", entry.getValue()), share);
            rank++;
        }

        // Write result.csv
        Path csvPath = Path.of("result.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("rank,word,count,share\n");
            rank = 1;
            for (var entry : sortedList) {
                double share = totalWords > 0 ? (entry.getValue() * 100.0 / totalWords) : 0.0;
                writer.write(String.format("%d,%s,%d,%.4f\n", rank, entry.getKey(), entry.getValue(), share));
                rank++;
            }
            System.out.println("result.csv written");
        } catch (IOException e) {
            System.err.println("Failed to write result.csv: " + e.getMessage());
        }
    }

    // Single-threaded reference implementation for verification
    public static Map<String, Long> countSingleThreaded(Path dir) throws IOException {
        Map<String, Long> map = new HashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            Iterable<Path> files = () -> paths.filter(Files::isRegularFile).iterator();
            for (Path file : files) {
                try (Stream<String> lines = Files.lines(file)) {
                    Iterable<String> it = lines::iterator;
                    for (String line : it) {
                        String[] tokens = WORD_SPLIT.split(line);
                        for (String token : tokens) {
                            if (!token.isEmpty()) {
                                map.merge(token.toLowerCase(), 1L, Long::sum);
                            }
                        }
                    }
                }
            }
        }
        return map;
    }

    // Helper to generate test data
    public static void generateTestData(Path dir, int numFiles, int wordsPerFile) throws IOException {
        Files.createDirectories(dir);
        List<String> vocab = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) {
            vocab.add("word" + i);
        }
        // Add common English words to top
        vocab.set(0, "the");
        vocab.set(1, "and");
        vocab.set(2, "of");
        vocab.set(3, "to");
        vocab.set(4, "a");

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int f = 1; f <= numFiles; f++) {
            Path file = dir.resolve("file_" + f + ".txt");
            try (BufferedWriter bw = Files.newBufferedWriter(file)) {
                StringBuilder line = new StringBuilder();
                for (int w = 0; w < wordsPerFile; w++) {
                    // Zipf-like distribution: bias heavily towards top words
                    int idx;
                    int r = rnd.nextInt(100);
                    if (r < 40) idx = rnd.nextInt(5);
                    else if (r < 70) idx = rnd.nextInt(50);
                    else idx = rnd.nextInt(vocab.size());

                    line.append(vocab.get(idx)).append(" ");
                    if ((w + 1) % 15 == 0) {
                        bw.write(line.toString().trim());
                        bw.newLine();
                        line.setLength(0);
                    }
                }
                if (line.length() > 0) {
                    bw.write(line.toString().trim());
                    bw.newLine();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("Usage: java ... WordCount <dir> [--top N] [--timeout SECONDS] [--virtual] [--workers N] [--generate N W]");
            return;
        }

        Path dir = Path.of(args[0]);
        int top = 10;
        long timeout = 0;
        boolean virtual = false;
        int workers = Runtime.getRuntime().availableProcessors();

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--top") && i + 1 < args.length) {
                top = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--timeout") && i + 1 < args.length) {
                timeout = Long.parseLong(args[++i]);
            } else if (args[i].equals("--virtual")) {
                virtual = true;
            } else if (args[i].equals("--workers") && i + 1 < args.length) {
                workers = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--generate") && i + 2 < args.length) {
                int count = Integer.parseInt(args[++i]);
                int words = Integer.parseInt(args[++i]);
                System.out.printf("Generating %d files with %d words in %s...%n", count, words, dir);
                generateTestData(dir, count, words);
                System.out.println("Done generating test data.");
                return;
            }
        }

        WordCount wc = new WordCount(dir, workers, virtual, top, timeout);
        wc.run();
    }
}
