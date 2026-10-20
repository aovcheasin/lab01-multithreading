package edu.itc.se.lab01.task3;

public interface OrderQueue {
    void put(Order order) throws InterruptedException;
    Order take() throws InterruptedException;
}
