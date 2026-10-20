package edu.itc.se.lab01.task2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Bench {

    // Step1
    public interface Counter{
        void increment();
        long get();
    }

    // Step2
    /*
     * Step 4: Recorded wrong results for UnsafeCounter (expected 8,000,000):
     * Run 1: value=7,553,567  correct=false  (38 ms)  - lost ~446,433 updates
     * Run 2: value=5,718,958  correct=false  (37 ms)  - lost ~2,281,042 updates
     * Run 3: value=4,923,286  correct=false  (32 ms)  - lost ~3,076,714 updates
     *
     * Cause: The non-atomic compound read-modify-write operation (count++) causes threads
     * to overwrite each other's updates concurrently (lost-update anomaly).
     */
    public static class UnsafeCounter implements Counter{
        private long count;
        
        @Override 
        public void increment(){
            count++;
        }
        @Override 
        public long get(){
            return count;
        }
    }

    // Step3
    static final int THREADS = 8;
    static final int ITERATIONS = 1_000_000;

    static void run(String label, Counter counter) throws InterruptedException {
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for(int t = 0; t < THREADS; t++){
            Thread th = new Thread(() -> {
                for(int i = 0; i < ITERATIONS; i++) counter.increment();
            }, "inc-" + t);
            threads.add(th);
            th.start();
        }
        for(Thread th: threads) th.join();
        long ms = (System.nanoTime() - start)/1_000_000;
        long expected = (long) THREADS * ITERATIONS;
        System.out.printf("%-22s value=%,d  correct=%-5b  %d ms%n",
                label, counter.get(), counter.get() == expected, ms);
    }

    // Step5
    public static class SynchronizedCounter implements Counter{
        private long count;
        @Override 
        public synchronized void increment(){
            count++;
        }

        @Override 
        public synchronized long get() {
            return count;
        }
    }

    public static class LockCounter implements Counter{
        private long count;
        private final Lock lock = new ReentrantLock();
        @Override 
        public void increment(){
            lock.lock();
            try{
                count++;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long get(){
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }
    }

    static public class AtomicCounter implements Counter{
        private final AtomicLong count = new AtomicLong();
        @Override 
        public void increment(){
            count.incrementAndGet();
        }
        @Override 
        public long get(){
            return count.get();
        }
    }

    // Step6: Bonus
    /*
     * Why LongAdder is faster under contention:
     * While AtomicLong uses a single shared 64-bit value updated via Compare-And-Swap (CAS),
     * multiple threads competing on the same cache line cause repeated CAS retries (cache bouncing).
     * LongAdder mitigates this by maintaining a dynamically striped array of Cell structures.
     * Each thread is hashed to a specific cell, allowing concurrent updates to occur on separate
     * cache lines without cross-core lockouts or spinning. Calling sum() aggregates all cells
     * and the base value when the tally is requested.
     */
    static public class LongAdderCounter implements Counter{
        private final LongAdder add = new LongAdder();

        @Override 
        public void increment(){
            add.increment();
        }

        @Override 
        public long get(){
            return add.sum();
        }
    }


    public static void main(String[] args) throws InterruptedException{
        run("UnsafeCounter", new UnsafeCounter());
        run("SynchronizedCounter", new SynchronizedCounter());
        run("LockCounter", new LockCounter());
        run("AtomicCounter", new AtomicCounter());
        run("LongAdderCounter", new LongAdderCounter());
    }

}
