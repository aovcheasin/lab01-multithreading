package edu.itc.se.lab01.task3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class Consumer implements Runnable {
    private final OrderQueue queue;
    private final AtomicInteger processedCounter;
    private final String name;

    public Consumer(OrderQueue queue, AtomicInteger processedCounter, String name) {
        this.queue = queue;
        this.processedCounter = processedCounter;
        this.name = name;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Order order = queue.take();
                if (order.id() == -1) {
                    System.out.println(name + " received poison pill, stopping");
                    break;
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(5, 31));
                System.out.printf("%s processed Order[id=%d, item=%s, qty=%d]%n",
                        name, order.id(), order.item(), order.qty());
                processedCounter.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
