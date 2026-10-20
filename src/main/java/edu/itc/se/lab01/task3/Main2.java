package edu.itc.se.lab01.task3;

public class Main2 {
    public static void main(String[] args) throws InterruptedException {
        Main.runScenario(new BlockingOrderQueue(5), "ArrayBlockingQueue (BlockingOrderQueue)");
    }
}
