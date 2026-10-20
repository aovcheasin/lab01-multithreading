package edu.itc.se.lab01.task4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class Main {

    static long countPrimes(long from, long to) {
        long count = 0;
        for (long n = Math.max(from, 2); n < to; n++) {
            boolean prime = true;
            for (long d = 2; d * d <= n; d++) {
                if (n % d == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) {
                count++;
            }
        }
        return count;
    }

    // Step 1: CPU-bound Callable
    public static record PrimeCounter(long from, long to) implements Callable<Long> {
        @Override
        public Long call() {
            return countPrimes(from, to);
        }
    }

    // Step 2: I/O-bound Callable
    public static record FakeDownload(String url, int taskId) implements Callable<String> {
        @Override
        public String call() throws Exception {
            // Timeouts requirement: One task (id 1) sleeps 5 seconds
            if (taskId == 1) {
                Thread.sleep(5000);
                return url + " -> 5000 bytes";
            }
            // Failure handling: Every 50th task throws IOException("boom") (0, 50, ..., 450 -> 10 tasks)
            if (taskId % 50 == 0) {
                throw new IOException("boom");
            }
            // Normal download: sleep 200 ms
            Thread.sleep(200);
            return url + " -> 200 bytes";
        }
    }

    static record BenchmarkResult(int ok, int failed, int timedOut, long elapsedMs) {}

    static BenchmarkResult runDownloads(ExecutorService executor) {
        long t0 = System.currentTimeMillis();
        int totalTasks = 500;
        List<Future<String>> futures = new ArrayList<>(totalTasks);

        for (int i = 0; i < totalTasks; i++) {
            futures.add(executor.submit(new FakeDownload("https://example.com/file-" + i, i)));
        }

        int ok = 0;
        int failed = 0;
        int timedOut = 0;

        // Await the timeout task first so the 1-second timeout is accurately measured
        Future<String> timeoutFuture = futures.get(1);
        try {
            timeoutFuture.get(1, TimeUnit.SECONDS);
            ok++;
        } catch (TimeoutException e) {
            timedOut++;
            timeoutFuture.cancel(true);
        } catch (ExecutionException e) {
            failed++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int i = 0; i < futures.size(); i++) {
            if (i == 1) continue; // Already processed
            Future<String> future = futures.get(i);
            try {
                future.get();
                ok++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    failed++;
                } else {
                    failed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new BenchmarkResult(ok, failed, timedOut, elapsed);
    }

    // Step 6 Bonus: CompletableFuture implementation
    static void runBonusCompletableFuture(ExecutorService executor, String label) {
        long t0 = System.currentTimeMillis();
        int totalTasks = 500;
        List<CompletableFuture<String>> futures = new ArrayList<>(totalTasks);

        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
                try {
                    if (taskId % 50 == 0) {
                        throw new CompletionException(new IOException("boom"));
                    }
                    Thread.sleep(200);
                    return "file-" + taskId + " (success)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
            }, executor).handle((res, ex) -> {
                if (ex != null) {
                    return "fallback-error";
                }
                return res;
            });
            futures.add(cf);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Bonus CompletableFuture (%s): 500 tasks completed in %d ms%n", label, elapsed);
    }

    public static void main(String[] args) throws Exception {
        final long LIMIT = 4_000_000;
        final int CHUNKS = 16;

        // 1. Single-threaded CPU prime counting
        long t0 = System.nanoTime();
        long single = countPrimes(0, LIMIT);
        long singleMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("single-threaded: %d primes in %d ms%n", single, singleMs);

        // 2. Parallel CPU prime counting with fixed thread pool
        int cores = Runtime.getRuntime().availableProcessors();
        t0 = System.nanoTime();
        long poolTotal = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(cores)) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < CHUNKS; i++) {
                long from = (long) i * LIMIT / CHUNKS;
                long to = (long) (i + 1) * LIMIT / CHUNKS;
                futures.add(pool.submit(new PrimeCounter(from, to)));
            }
            for (Future<Long> f : futures) {
                poolTotal += f.get();
            }
        }
        long poolMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("pool (%d threads): %d primes in %d ms%n", cores, poolTotal, poolMs);

        // 3. I/O-bound platform pool (20 threads)
        BenchmarkResult resPlatform;
        try (ExecutorService platformPool = Executors.newFixedThreadPool(20)) {
            resPlatform = runDownloads(platformPool);
        }
        System.out.printf("downloads on fixed(20) platform threads : ok=%d failed=%d timedOut=%d in %d ms%n",
                resPlatform.ok(), resPlatform.failed(), resPlatform.timedOut(), resPlatform.elapsedMs());

        // 4. I/O-bound virtual thread executor
        BenchmarkResult resVirtual;
        try (ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor()) {
            resVirtual = runDownloads(virtualPool);
        }
        System.out.printf("downloads on virtual threads            : ok=%d failed=%d timedOut=%d in %d ms%n",
                resVirtual.ok(), resVirtual.failed(), resVirtual.timedOut(), resVirtual.elapsedMs());

        // 5. Bonus CompletableFuture demonstration
        try (ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor()) {
            runBonusCompletableFuture(virtualPool, "Virtual Threads");
        }
    }
}
