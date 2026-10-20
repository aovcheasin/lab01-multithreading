package edu.itc.se.lab01.task3;

import java.util.concurrent.ThreadLocalRandom;

public class Producer implements Runnable {
    private final OrderQueue queue;
    private final int startId;
    private final int count;
    private final int poisonPills;

    public Producer(OrderQueue queue, int startId, int count, int poisonPills) {
        this.queue = queue;
        this.startId = startId;
        this.count = count;
        this.poisonPills = poisonPills;
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < count; i++) {
                int id = startId + i;
                int qty = ThreadLocalRandom.current().nextInt(1, 6);
                queue.put(new Order(id, "item-" + id, qty));
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 21));
            }
            for (int p = 0; p < poisonPills; p++) {
                queue.put(new Order(-1, "POISON", 0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
