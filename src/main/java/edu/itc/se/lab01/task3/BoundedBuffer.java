package edu.itc.se.lab01.task3;

import java.util.ArrayDeque;

public class BoundedBuffer implements OrderQueue {
    private final ArrayDeque<Order> items = new ArrayDeque<>();
    private final int capacity;

    public BoundedBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void put(Order order) throws InterruptedException {
        while (items.size() >= capacity) {
            wait();
        }
        items.addLast(order);
        assert items.size() <= capacity : "Buffer capacity exceeded: " + items.size() + " > " + capacity;
        notifyAll();
    }

    @Override
    public synchronized Order take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        assert !items.isEmpty() : "Attempted to take from empty buffer";
        Order order = items.removeFirst();
        notifyAll();
        return order;
    }

    public synchronized int size() {
        return items.size();
    }
}
