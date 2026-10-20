package edu.itc.se.lab01.task5;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Main {

    public static final class Account {
        final int id;
        private long balance;
        final ReentrantLock lock = new ReentrantLock();

        Account(int id, long balance) {
            this.id = id;
            this.balance = balance;
        }

        public long balance() {
            return balance;
        }

        public void withdraw(long amount) {
            balance -= amount;
        }

        public void deposit(long amount) {
            balance += amount;
        }
    }

    // Step 3: Watchdog daemon thread that detects deadlocks
    static Thread startWatchdog() {
        Thread watchdog = new Thread(() -> {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long[] ids = bean.findDeadlockedThreads();
                if (ids != null && ids.length > 0) {
                    ThreadInfo[] infos = bean.getThreadInfo(ids);
                    List<String> names = Arrays.stream(infos)
                            .map(ThreadInfo::getThreadName)
                            .toList();
                    System.out.printf("[watchdog] DEADLOCK detected between: %s (process hangs - Ctrl+C)%n",
                            String.join(", ", names));
                    break;
                }
            }
        }, "watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    // Deliberately broken transfer from starter code
    static void transferBroken(Account from, Account to, long amount) {
        synchronized (from) {
            synchronized (to) {
                if (from.balance() >= amount) {
                    from.withdraw(amount);
                    to.deposit(amount);
                }
            }
        }
    }

    // Step 4: Fix A - OrderedBank acquires monitors in ascending id order
    static void transferOrdered(Account a, Account b, long amount) {
        Account first = a.id < b.id ? a : b;
        Account second = a.id < b.id ? b : a;

        synchronized (first) {
            synchronized (second) {
                if (a.balance() >= amount) {
                    a.withdraw(amount);
                    b.deposit(amount);
                }
            }
        }
    }

    // Step 5: Fix B - TryLockBank using tryLock with backoff and retry count
    static final AtomicLong retryCounter = new AtomicLong();

    static void transferTryLock(Account a, Account b, long amount) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (a.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    if (b.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                        try {
                            if (a.balance() >= amount) {
                                a.withdraw(amount);
                                b.deposit(amount);
                            }
                            return; // Transfer complete
                        } finally {
                            b.lock.unlock();
                        }
                    }
                } finally {
                    a.lock.unlock(); // Always release first lock before backing off (avoids hold-and-wait)
                }
            }
            retryCounter.incrementAndGet();
            // Random back-off
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10));
        }
    }

    public static void runBrokenWithWatchdog() throws InterruptedException {
        System.out.println("# broken version, with watchdog");
        startWatchdog();

        Account[] accounts = new Account[4];
        for (int i = 0; i < accounts.length; i++) accounts[i] = new Account(i, 1_000);

        Thread[] workers = new Thread[8];
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 100_000; i++) {
                    Account a = accounts[rnd.nextInt(accounts.length)];
                    Account b = accounts[rnd.nextInt(accounts.length)];
                    if (a != b) transferBroken(a, b, rnd.nextInt(1, 50));
                }
            }, "transfer-" + w);
            workers[w].start();
        }

        for (Thread t : workers) t.join();
    }

    public static void runOrderedBank() throws InterruptedException {
        System.out.println("\n# OrderedBank");
        Account[] accounts = new Account[4];
        for (int i = 0; i < accounts.length; i++) accounts[i] = new Account(i, 1_000);

        int threadCount = 8;
        int transfersPerThread = 100_000;
        Thread[] workers = new Thread[threadCount];

        long start = System.currentTimeMillis();
        for (int w = 0; w < threadCount; w++) {
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                for (int i = 0; i < transfersPerThread; i++) {
                    Account a = accounts[rnd.nextInt(accounts.length)];
                    Account b = accounts[rnd.nextInt(accounts.length)];
                    if (a != b) transferOrdered(a, b, rnd.nextInt(1, 50));
                }
            }, "ordered-transfer-" + w);
            workers[w].start();
        }

        for (Thread t : workers) t.join();
        long elapsed = System.currentTimeMillis() - start;

        long total = 0;
        for (Account a : accounts) total += a.balance();
        System.out.printf("transfers=%d total=%d (expected 4000) in %d ms%n",
                (long) threadCount * transfersPerThread, total, elapsed);
        assert total == 4000 : "Total balance mismatch: " + total;
    }

    public static void runTryLockBank() throws InterruptedException {
        System.out.println("\n# TryLockBank");
        retryCounter.set(0);
        Account[] accounts = new Account[4];
        for (int i = 0; i < accounts.length; i++) accounts[i] = new Account(i, 1_000);

        int threadCount = 8;
        int transfersPerThread = 100_000;
        Thread[] workers = new Thread[threadCount];

        long start = System.currentTimeMillis();
        for (int w = 0; w < threadCount; w++) {
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                for (int i = 0; i < transfersPerThread; i++) {
                    Account a = accounts[rnd.nextInt(accounts.length)];
                    Account b = accounts[rnd.nextInt(accounts.length)];
                    if (a != b) {
                        try {
                            transferTryLock(a, b, rnd.nextInt(1, 50));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "trylock-transfer-" + w);
            workers[w].start();
        }

        for (Thread t : workers) t.join();
        long elapsed = System.currentTimeMillis() - start;

        long total = 0;
        for (Account a : accounts) total += a.balance();
        System.out.printf("transfers=%d retries=%d total=%d (expected 4000) in %d ms%n",
                (long) threadCount * transfersPerThread, retryCounter.get(), total, elapsed);
        assert total == 4000 : "Total balance mismatch: " + total;
    }

    // Step 6: Graceful cooperative shutdown
    public static void runGracefulShutdown() throws InterruptedException {
        System.out.println("\n# graceful shutdown");
        Account[] accounts = new Account[4];
        for (int i = 0; i < accounts.length; i++) accounts[i] = new Account(i, 1_000);

        int threadCount = 8;
        Thread[] workers = new Thread[threadCount];
        long[] completedTransfers = new long[threadCount];

        for (int w = 0; w < threadCount; w++) {
            final int workerIdx = w;
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                long count = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    Account a = accounts[rnd.nextInt(accounts.length)];
                    Account b = accounts[rnd.nextInt(accounts.length)];
                    if (a != b) {
                        try {
                            transferTryLock(a, b, rnd.nextInt(1, 50));
                            count++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Restore interrupt flag
                            break;
                        }
                    }
                }
                completedTransfers[workerIdx] = count;
            }, "transfer-" + w);
            workers[w].start();
        }

        // Run for 2 seconds
        Thread.sleep(2000);
        System.out.println("[main] interrupting 8 workers after 2000 ms");

        for (Thread t : workers) {
            t.interrupt();
        }

        boolean allStopped = true;
        for (int w = 0; w < threadCount; w++) {
            workers[w].join(1000);
            boolean alive = workers[w].isAlive();
            if (alive) {
                allStopped = false;
            }
            System.out.printf("transfer-%d stopped after %d transfers (isAlive=%b)%n",
                    w, completedTransfers[w], alive);
        }

        long total = 0;
        for (Account a : accounts) total += a.balance();
        System.out.printf("all workers stopped=%b total=%d (expected 4000)%n",
                allStopped, total);
        assert total == 4000 : "Invariant violated: balance is " + total;
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0 && args[0].equals("--deadlock")) {
            runBrokenWithWatchdog();
            return;
        }

        // Run both fixes and graceful shutdown
        runOrderedBank();
        runTryLockBank();
        runGracefulShutdown();
    }
}
