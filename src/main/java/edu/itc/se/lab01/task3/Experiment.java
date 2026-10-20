package edu.itc.se.lab01.task3;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 7 Experiment:
 * Demonstrates what happens when changing notifyAll() to notify() and while to if.
 */
public class Experiment {

    // Flawed buffer with 'if' instead of 'while' and 'notify()' instead of 'notifyAll()'
    public static class FlawedBuffer implements OrderQueue {
        private final ArrayDeque<Order> items = new ArrayDeque<>();
        private final int capacity;

        public FlawedBuffer(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public synchronized void put(Order order) throws InterruptedException {
            if (items.size() >= capacity) { // FLAW 1: 'if' instead of 'while'
                wait();
            }
            items.addLast(order);
            notify(); // FLAW 2: 'notify()' instead of 'notifyAll()'
        }

        @Override
        public synchronized Order take() throws InterruptedException {
            if (items.isEmpty()) { // FLAW 1: 'if' instead of 'while'
                wait();
            }
            Order order = items.removeFirst();
            notify(); // FLAW 2: 'notify()' instead of 'notifyAll()'
            return order;
        }
    }

    public static void main(String[] args) {
        System.out.println("Running FlawedBuffer experiment 20 times...");
        for (int run = 1; run <= 20; run++) {
            System.out.printf("--- Run %d ---%n", run);
            try {
                // If it deadlocks/hangs, interrupt after 3 seconds
                Thread testThread = new Thread(() -> {
                    try {
                        Main.runScenario(new FlawedBuffer(5), "FlawedBuffer");
                    } catch (Exception e) {
                        System.out.println("Encountered exception: " + e.getMessage());
                    }
                });
                testThread.start();
                testThread.join(3000);
                if (testThread.isAlive()) {
                    System.err.printf("DEADLOCK / HANG DETECTED at run %d! Thread is still blocked.%n", run);
                    testThread.interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
