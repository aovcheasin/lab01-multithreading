# Lab 01: Multithreading in Java

**Course:** Software Engineering — Chapter 01  
**Student Submission:** Multithreading & Concurrency Laboratory  
**Runtime Environment:** Java 25 LTS (`build 25+37-LTS-3491`), Apache Maven 3.9.16, Windows 11  

---

## Task 1: Creating and Running Threads

### 1. `run()` vs `start()` Behavior and Explanation
- **Concurrent Mode (`start()`):** When `start()` is invoked on a `Thread` object, the JVM creates a new execution context (an OS platform thread or a virtual thread) scheduled independently. In `Main.java`, calling `start()` on the four threads (`apollo`, `gemini`, `mercury`, `artemis`) allows the main thread to immediately proceed and print `"Mission control: all launched"`, while countdowns interleave asynchronously.
- **Sequential Mode (`run()` via `--sequential`):** Calling `run()` directly performs a standard synchronous method invocation on the calling thread (`main`). No new OS or virtual threads are spawned. Each countdown runs strictly to completion sequentially on `main`, meaning `Thread.currentThread().getName()` reports `main` (for `gemini` and `mercury`), and `"Mission control: all launched"` does not print until all countdowns have already finished.
- **Virtual Thread Behavior:** In standard platform threads, calling `t.run()` executes the target `Runnable`. However, in OpenJDK's `VirtualThread`, `run()` is implemented as a no-op (`return;`) because virtual threads are bound to a continuation scheduler and carrier thread pool initiated exclusively through `start()`. Furthermore, without calling `start()`, thread states remain `NEW`.

---

## Task 2: Fixing a Race Condition

### 1. Benchmark Results on Shared Counter (8 Threads $\times$ 1,000,000 Iterations)
Target expected count: **8,000,000**

| Implementation | Value | Correct | Elapsed Time | Concurrency Strategy |
| :--- | :--- | :---: | :---: | :--- |
| **`UnsafeCounter`** | 5,718,958 | ❌ `false` | 37 ms | Plain `long count` (`count++` lost updates) |
| **`SynchronizedCounter`** | 8,000,000 | ✅ `true` | 453 ms | Intrinsic monitor locks on methods |
| **`AtomicCounter`** | 8,000,000 | ✅ `true` | 116 ms | Single CAS loop (`AtomicLong`) |
| **`LockCounter`** | 8,000,000 | ✅ `true` | 79 ms | Explicit `ReentrantLock` (`try...finally`) |
| **`LongAdderCounter`** | 8,000,000 | ✅ `true` | 16 ms | Striped 64-bit cells (`LongAdder`) |

*Recorded UnsafeCounter runs on this machine:*
- Run 1: `value=7,553,567  correct=false  (38 ms)`
- Run 2: `value=5,718,958  correct=false  (37 ms)`
- Run 3: `value=4,923,286  correct=false  (32 ms)`

### 2. Why Synchronizing only `increment()` but not `get()` Still Leaves a Bug
Under the **Java Memory Model (JMM)**, thread synchronization establishes a *happens-before* guarantee between releasing a lock and subsequent acquisitions of that **exact same lock**.

If `increment()` is `synchronized` but `get()` is not:
1. **Lack of Visibility / Stale Reads:** The reader thread calling `get()` does not acquire the monitor. Therefore, it does not execute a memory barrier (acquire/load fence) and can read a stale, cached value from CPU L1/L2 registers rather than the freshly flushed value in main memory.
2. **Missing Happens-Before Edge:** Even if all worker threads have completed and joined, an asynchronous reader querying `get()` concurrently during execution has no *happens-before* order relative to writer threads.
3. **64-bit Word Tearing:** On 32-bit JVM architectures, writes to non-volatile, unsynchronized 64-bit `long` fields are treated as two separate 32-bit operations, allowing a reader to observe high bits from one write and low bits from another.

### 3. Why `LongAdder` is Faster Under High Contention
`AtomicLong` relies on a single shared memory location updated via Compare-And-Swap (CAS) in a spin loop. When 8 CPU cores contend to update the same 64-bit address simultaneously, CAS operations frequently fail, causing threads to spin in retry loops and creating heavy CPU cache-line bouncing (MESI invalidation storm across cores).

`LongAdder` eliminates this bottleneck by maintaining a dynamically striped array of 64-bit `Cell` structures padded against false sharing. Each thread is hashed to a different cell index, allowing parallel increments to proceed on separate CPU cache lines without cross-core lockouts. The total count is aggregated only when `sum()` is invoked.

---

## Task 3: Producer-Consumer with Guarded Blocks and BlockingQueue

### 1. Why `while` and `notifyAll()` Are Strictly Required
- **`while` vs `if` (Spurious and Concurrent Wakeups):**
  When a thread is suspended in `wait()`, it releases the object monitor. When it wakes up (due to a signal or an OS spurious wakeup), it must compete to re-acquire the monitor. By the time it acquires the monitor, another woken thread may have already emptied the buffer or filled it to capacity.
  - Using `if` causes the thread to resume execution without re-validating the condition predicate, leading to `NoSuchElementException` (taking from an empty deque) or buffer overflow.
  - Using `while` guarantees that the thread re-evaluates the condition predicate after waking up and re-enters `wait()` if the condition is not satisfied.
- **`notifyAll()` vs `notify()` (Lost Wakeup / Deadlock):**
  In a shared monitor with both producers and consumers waiting on the same condition queue:
  - If a producer puts an item and calls `notify()`, the JVM may wake another *producer* instead of a consumer. If the buffer is full, the woken producer immediately goes back to sleep. The signal is lost, consumers remain sleeping, and all threads eventually freeze in an artificial deadlock.
  - `notifyAll()` wakes all waiting threads, guaranteeing that eligible consumers (or producers) make progress.

### 2. Empirical Findings from `Experiment.java`
Running `FlawedBuffer` (using `if` instead of `while` and `notify()` instead of `notifyAll()`) 20 times immediately reproduced catastrophic failures:
- **`NoSuchElementException`:** In 7 out of 20 runs, a consumer woke up, saw another consumer had already taken the order, and threw:
  ```
  Exception in thread "consumer-2" java.util.NoSuchElementException
      at java.base/java.util.ArrayDeque.removeFirst(ArrayDeque.java:360)
      at edu.itc.se.lab01.task3.Experiment$FlawedBuffer.take(Experiment.java:35)
  ```
- With proper `while` loops and `notifyAll()` in [`BoundedBuffer.java`](file:///e:/I4-GIC/Software%20Engineer/lab01-multithreading%20-%20Copy/src/main/java/edu/itc/se/lab01/task3/BoundedBuffer.java), all 100 orders are reliably processed across repeated runs without drops or deadlocks.

---

## Task 4: Thread Pools, Future, and Virtual Threads

### 1. Performance Comparison
- **CPU-Bound Prime Counting (4,000,000 limit, trial division):**
  - Single-threaded: `283,146 primes in 803 ms`
  - Fixed Thread Pool (12 cores, 16 chunks): `283,146 primes in 194 ms`
  - **Speedup:** **~4.14x speedup** on 12 available processor cores.
- **I/O-Bound Downloads (500 tasks, 200ms simulated latency):**
  - Platform Pool (20 OS threads): `ok=489 failed=10 timedOut=1 in 5,018 ms`
  - Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`): `ok=489 failed=10 timedOut=1 in 1,010 ms`

### 2. Why Virtual Threads are ~5x Faster on I/O Workloads
With 20 platform threads, 500 tasks with 200ms latency can only process 20 tasks concurrently at any time ($\frac{500}{20} \times 200\text{ ms} \approx 5,000\text{ ms}$). The OS threads are blocked in kernel sleep states, consuming OS stack memory and context-switching overhead.

With virtual threads, all 500 tasks execute concurrently. When a virtual thread executes a blocking operation (`Thread.sleep` or network I/O), it unmounts from its carrier thread and parks in the JVM heap, freeing the carrier OS thread to execute other virtual tasks. The total duration is determined solely by the single slowest task / timeout ($\approx 1.0\text{ s}$ timeout) rather than serialized batches.

---

## Task 5: Diagnosing Deadlock and Shutting Down Gracefully

### 1. Deadlock Diagnosis & `deadlock.txt`
The original `Bank` class produced a circular wait deadlock because locks on accounts were acquired in arbitrary order (`synchronized (from) { synchronized (to) { ... } }`).
Using JDK 25's `jstack`, the deadlock was diagnosed and captured in [`deadlock.txt`](file:///e:/I4-GIC/Software%20Engineer/lab01-multithreading%20-%20Copy/deadlock.txt):
```
Found one Java-level deadlock:
=============================
"transfer-0":
  waiting to lock monitor 0x... (object Account id=2), held by "transfer-3"
"transfer-3":
  waiting to lock monitor 0x... (object Account id=0), held by "transfer-6"
"transfer-6":
  waiting to lock monitor 0x... (object Account id=2), held by "transfer-3"
```
The runtime watchdog daemon thread monitors `ManagementFactory.getThreadMXBean().findDeadlockedThreads()` and immediately reports:
```
[watchdog] DEADLOCK detected between: transfer-0, transfer-1, transfer-4 (process hangs - Ctrl+C)
```

### 2. Fix A: `OrderedBank` (Lock Ordering)
- **Mechanism:** Enforces a strict total ordering over resource acquisition. Regardless of transfer direction, accounts are always locked in ascending order of `account.id`:
  ```java
  Account first = a.id < b.id ? a : b;
  Account second = a.id < b.id ? b : a;
  synchronized (first) {
      synchronized (second) { ... }
  }
  ```
- **Result:** Breaks Coffman's **Circular Wait** condition. 800,000 transfers complete in **73 ms** with balance invariant preserved ($4,000$).

### 3. Fix B: `TryLockBank` (Back-Off & Retry)
- **Mechanism:** Uses `ReentrantLock.tryLock(10, TimeUnit.MILLISECONDS)`. If the second account lock cannot be acquired within 10ms, the worker **releases the first lock** and backs off with a randomized sleep before retrying.
- **Result:** Breaks Coffman's **Hold and Wait** condition. 800,000 transfers complete with 1,087 retries in **3,262 ms** with balance invariant preserved ($4,000$).

### 4. Graceful Cooperative Interruption
Workers check `!Thread.currentThread().isInterrupted()`. When interrupted by the main thread after 2 seconds:
- Workers catch `InterruptedException`, restore the flag via `Thread.currentThread().interrupt()`, and exit cleanly.
- Main joins with a 1-second timeout (`join(1000)`), checks `t.isAlive() == false`, and verifies that total balance remains exactly $4,000$.

---

## Challenge: Parallel Word-Frequency Analyser

### 1. Scaling Benchmark Table (200 Files $\times$ 50,000 Words = 10,000,000 Total Words)

| Worker Mode | Elapsed Time | Throughput (words/sec) | Speedup vs 1 Worker |
| :--- | :---: | :---: | :---: |
| **Platform Worker (1)** | 4.53 s | 2,207,505 w/s | 1.00x |
| **Platform Workers (2)** | 0.80 s | 12,500,000 w/s | 5.66x |
| **Platform Workers (4)** | 0.71 s | 14,084,507 w/s | 6.38x |
| **Platform Workers (8)** | 0.76 s | 13,157,894 w/s | 5.96x |
| **Virtual Threads** | **0.42 s** | **23,809,523 w/s** | **10.78x** |

### 2. Why `ConcurrentHashMap<String, LongAdder>` Beats Map Synchronization & `merge`
- **Global Synchronization:** Synchronizing the entire map serializes all updates across all worker threads into a single critical section, completely eliminating multi-core scaling.
- **`Map.merge` with Atomic/Long:** `map.merge(word, 1L, Long::sum)` requires modifying the node entry inside the bucket under that specific bucket's lock. For high-frequency words (e.g. "the", "a", "of"), all threads continuously collide on the exact same bucket lock.
- **`computeIfAbsent` with `LongAdder`:**
  ```java
  counts.computeIfAbsent(word, k -> new LongAdder()).increment();
  ```
  The bucket lock is acquired *only once* when the word is first inserted into the map. Once the `LongAdder` instance exists in the map, all subsequent word increments occur entirely lock-free through cell striping on the `LongAdder` without holding any map bucket lock.

### 3. Coffman Conditions Analysis in Pipeline Architecture
The pipeline eliminates deadlocks by ruling out the **Hold and Wait** and **Circular Wait** Coffman conditions:
1. **Unidirectional Work Flow (No Circular Wait):** The architecture is a directed acyclic pipeline (Scanner $\rightarrow$ `BlockingQueue<Path>` $\rightarrow$ Workers $\rightarrow$ `ConcurrentHashMap`). Threads never wait on each other in a cycle; producers only feed forward, and consumers only pull forward.
2. **Single Resource Acquisition (No Hold and Wait):** Workers only hold one lock or take from one queue at a time. A worker takes a file path from the bounded queue, processes the file independently, and updates the concurrent map without holding any queue lock. Back-pressure is maintained naturally via `ArrayBlockingQueue(16)`.

---

## Verification & Test Suite Summary

- `mvn clean verify` succeeds with **0 failures and 0 errors**.
- All unit tests in `WordCountTest.java` pass:
  1. `testParallelMatchesSingleThreadedReference`: Validates that parallel word counting yields results 100% identical to the reference single-threaded implementation.
  2. `testTimeoutStopsWithinDeadline`: Validates that `--timeout 1` cleanly stops the tool and exits within 3 seconds even on massive workloads (5,000 files).
