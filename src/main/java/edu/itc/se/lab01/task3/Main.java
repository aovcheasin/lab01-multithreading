package edu.itc.se.lab01.task3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        runScenario(new BoundedBuffer(5), "BoundedBuffer");
    }

    public static void runScenario(OrderQueue queue, String bufferName) throws InterruptedException {
        long start = System.currentTimeMillis();
        AtomicInteger totalProcessed = new AtomicInteger();

        int producerCount = 2;
        int ordersPerProducer = 50;
        int consumerCount = 3;

        // 3 consumers
        List<Thread> consumers = new ArrayList<>();
        for (int i = 1; i <= consumerCount; i++) {
            Thread c = new Thread(new Consumer(queue, totalProcessed, "consumer-" + i), "consumer-" + i);
            consumers.add(c);
            c.start();
        }

        // 2 producers
        List<Thread> producers = new ArrayList<>();
        for (int i = 0; i < producerCount; i++) {
            int startId = i * ordersPerProducer + 1;
            Thread p = new Thread(new Producer(queue, startId, ordersPerProducer, 0), "producer-" + (i + 1));
            producers.add(p);
            p.start();
        }

        // Wait for all producers to finish
        for (Thread p : producers) {
            p.join();
        }

        // Put poison pills to signal consumers to terminate
        for (int i = 0; i < consumerCount; i++) {
            queue.put(new Order(-1, "POISON", 0));
        }

        // Wait for all consumers to finish
        for (Thread c : consumers) {
            c.join();
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Total processed: %d (expected %d) in %d ms using %s%n",
                totalProcessed.get(), producerCount * ordersPerProducer, elapsed, bufferName);

        assert totalProcessed.get() == producerCount * ordersPerProducer :
                "Expected " + (producerCount * ordersPerProducer) + " orders processed, but got " + totalProcessed.get();
    }
}
