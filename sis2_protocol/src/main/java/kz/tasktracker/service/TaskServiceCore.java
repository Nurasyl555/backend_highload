package kz.tasktracker.service;

import kz.tasktracker.model.TaskDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TaskServiceCore {
    private final ConcurrentHashMap<Long, TaskDto> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskDto> idempotencyStore = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1000);

    // Fault injection for timeout & retry testing
    private volatile long simulatedDelayMs = 0;
    private final AtomicInteger simulatedFailuresRemaining = new AtomicInteger(0);

    public TaskDto createTask(String idempotencyKey, long userId, String title, String description,
                              int priority, long deadline, boolean[] isDuplicateOut) {
        checkInjectedFaults();

        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            TaskDto cached = idempotencyStore.get(idempotencyKey);
            if (cached != null) {
                if (isDuplicateOut != null && isDuplicateOut.length > 0) {
                    isDuplicateOut[0] = true;
                }
                return cached;
            }
        }

        // 2. Create new task
        long id = idSequence.incrementAndGet();
        long now = System.currentTimeMillis();
        TaskDto newTask = new TaskDto(
                id, userId, title, description, "TODO", priority, deadline, 1, now, now
        );

        tasks.put(id, newTask);

        // 3. Atomically store idempotency key
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            TaskDto previous = idempotencyStore.putIfAbsent(idempotencyKey, newTask);
            if (previous != null) {
                if (isDuplicateOut != null && isDuplicateOut.length > 0) {
                    isDuplicateOut[0] = true;
                }
                return previous;
            }
        }

        if (isDuplicateOut != null && isDuplicateOut.length > 0) {
            isDuplicateOut[0] = false;
        }
        return newTask;
    }

    public TaskDto getTask(long id) {
        checkInjectedFaults();
        return tasks.get(id);
    }

    public List<TaskDto> listTasks(long userId, int limit) {
        checkInjectedFaults();
        List<TaskDto> result = new ArrayList<>();
        for (TaskDto task : tasks.values()) {
            if (userId <= 0 || task.getUserId() == userId) {
                result.add(task);
            }
        }
        result.sort(Comparator.comparingLong(TaskDto::getId));
        if (limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    public void clear() {
        tasks.clear();
        idempotencyStore.clear();
        simulatedDelayMs = 0;
        simulatedFailuresRemaining.set(0);
    }

    public int getTaskCount() {
        return tasks.size();
    }

    public void setSimulatedDelayMs(long delayMs) {
        this.simulatedDelayMs = delayMs;
    }

    public void setSimulatedFailures(int count) {
        this.simulatedFailuresRemaining.set(count);
    }

    private void checkInjectedFaults() {
        if (simulatedFailuresRemaining.get() > 0) {
            if (simulatedFailuresRemaining.decrementAndGet() >= 0) {
                throw new IllegalStateException("Simulated transient server failure (503 / UNAVAILABLE)");
            }
        }

        if (simulatedDelayMs > 0) {
            try {
                Thread.sleep(simulatedDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Execution interrupted during simulated delay", e);
            }
        }
    }
}
