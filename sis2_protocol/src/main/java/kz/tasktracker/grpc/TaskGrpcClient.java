package kz.tasktracker.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.tasktracker.proto.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TaskGrpcClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final TaskGrpcServiceGrpc.TaskGrpcServiceBlockingStub stub;

    public TaskGrpcClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = TaskGrpcServiceGrpc.newBlockingStub(channel);
    }

    public CreateTaskResponse createTaskWithRetry(CreateTaskRequest request, long timeoutMs, int maxRetries) {
        int attempt = 0;
        long baseBackoffMs = 20;

        while (true) {
            attempt++;
            try {
                return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                        .createTask(request);
            } catch (StatusRuntimeException e) {
                boolean isTransient = e.getStatus().getCode() == Status.Code.UNAVAILABLE
                        || e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED;

                // Idempotent requests (with idempotency_key) can safely be retried on transient errors
                boolean canRetry = isTransient && request.getIdempotencyKey() != null
                        && !request.getIdempotencyKey().isBlank()
                        && attempt <= maxRetries;

                if (!canRetry) {
                    throw e;
                }

                // Exponential Backoff with Full Jitter
                long maxSleep = (long) (baseBackoffMs * Math.pow(2, attempt - 1));
                long sleepMs = ThreadLocalRandom.current().nextLong(maxSleep / 2, maxSleep + 1);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    public GetTaskResponse getTask(long id, long timeoutMs) {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getTask(GetTaskRequest.newBuilder().setId(id).build());
    }

    public ListTasksResponse listTasks(long userId, int limit, long timeoutMs) {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .listTasks(ListTasksRequest.newBuilder().setUserId(userId).setLimit(limit).build());
    }

    @Override
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }
}
