package edu.itc.se.lab01.task1;

/*
 * Task 1: Explanation of run() vs start():
 *
 * 1. Concurrency vs. Synchronous Execution:
 *    - When start() is called, the JVM and OS schedule a new thread of execution (an OS thread
 *      for platform threads, or a carrier-thread-backed continuation for virtual threads).
 *      Countdown tasks execute concurrently in the background, allowing "Mission control: all launched"
 *      to print immediately before any countdown completes.
 *    - When run() is called directly (via --sequential), no new thread is created. The run()
 *      method executes synchronously on the calling thread ("main"). Execution proceeds
 *      step-by-step, blocking sequential progress.
 *
 * 2. Thread Identity:
 *    - In concurrent mode, Thread.currentThread().getName() returns the designated names
 *      ("apollo", "gemini", "mercury", "artemis").
 *    - In --sequential mode, because CountdownTask runs on the main thread,
 *      Thread.currentThread().getName() resolves to "main".
 *
 * 3. Virtual Thread Behavior:
 *    - Standard platform threads execute their target Runnable when run() is invoked.
 *    - In contrast, JDK VirtualThread overrides run() as a no-op (Code: return;) because
 *      virtual threads require a carrier thread and continuation scheduler initiated by start().
 *    - After calling run() without start(), thread states remain NEW rather than TERMINATED,
 *      and join() returns immediately.
 */
public class Main {
    
    // Step 1: Thread subclass
    public static class Countdown extends Thread {
        public Countdown(String name) {
            super(name);
        }
        
        @Override 
        public void run() {
            for (int i = 3; i >= 1; i--) {
                System.out.println(getName() + ": " + i);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println(getName() + ": liftoff!");
        }
    }

    // Step 2: Runnable task
    public static class CountdownTask implements Runnable {
        @Override 
        public void run() {
            for (int i = 3; i >= 1; i--) {
                System.out.println(Thread.currentThread().getName() + ": " + i);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println(Thread.currentThread().getName() + ": liftoff!");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean sequential = args.length > 0 && args[0].equals("--sequential");
        
        // Step 3: Four creation techniques
        Thread apollo = new Countdown("apollo");
        Thread gemini = new Thread(new CountdownTask(), "gemini");
        Thread mercury = new Thread(() -> new CountdownTask().run(), "mercury");
        Thread artemis = Thread.ofVirtual().name("artemis").unstarted(new CountdownTask());
        Thread[] threads = {apollo, gemini, mercury, artemis};

        // Step 4 & 5: Launch or run sequentially
        for (Thread t : threads) {
            if (sequential) {
                t.run();
            } else {
                t.start();
            }
        }
        System.out.println("Mission control: all launched");
        
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Mission control: all landed");

        // Step 6: Print thread properties
        for (Thread t : threads) {
            System.out.printf("%-8s virtual=%-5b state=%s%n", t.getName(), t.isVirtual(), t.getState());
        }
    }
}
