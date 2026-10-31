package kz.tasktracker;

import io.grpc.StatusRuntimeException;
import kz.tasktracker.grpc.TaskGrpcClient;
import kz.tasktracker.grpc.TaskGrpcServer;
import kz.tasktracker.model.CreateTaskDto;
import kz.tasktracker.model.TaskResponseDto;
import kz.tasktracker.proto.CreateTaskRequest;
import kz.tasktracker.proto.CreateTaskResponse;
import kz.tasktracker.proto.TaskPriority;
import kz.tasktracker.rest.TaskHttpServer;
import kz.tasktracker.rest.TaskRestClient;
import kz.tasktracker.service.TaskServiceCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TimeoutAndRetryTest {
    private static final int REST_PORT = 18081;
    private static final int GRPC_PORT = 15052;

    private static TaskServiceCore core;
    private static TaskHttpServer restServer;
    private static TaskGrpcServer grpcServer;

    @BeforeAll
    public static void setupServers() throws Exception {
        core = new TaskServiceCore();
        restServer = new TaskHttpServer(REST_PORT, core);
        restServer.start();

        grpcServer = new TaskGrpcServer(GRPC_PORT, core);
        grpcServer.start();
    }

    @AfterAll
    public static void teardownServers() {
        if (restServer != null) restServer.stop();
        if (grpcServer != null) grpcServer.stop();
    }

    @BeforeEach
    public void reset() {
        core.clear();
    }

    @Test
    public void testGrpcTimeoutEnforcement() {
        // Server simulated delay: 250ms
        core.setSimulatedDelayMs(250);

        try (TaskGrpcClient client = new TaskGrpcClient("localhost", GRPC_PORT)) {
            CreateTaskRequest req = CreateTaskRequest.newBuilder()
                    .setUserId(2001)
                    .setTitle("Slow Task")
                    .build();

            // Client deadline: 50ms (0 retries)
            assertThrows(StatusRuntimeException.class, () -> {
                client.createTaskWithRetry(req, 50, 0);
            }, "Expected StatusRuntimeException due to deadline exceeded");
        }
    }

    @Test
    public void testRestTimeoutEnforcement() {
        core.setSimulatedDelayMs(250);

        try (TaskRestClient client = new TaskRestClient("localhost", REST_PORT)) {
            CreateTaskDto req = new CreateTaskDto(2001, "Slow Task", "Desc", 1, 0);

            assertThrows(IOException.class, () -> {
                client.createTaskWithRetry(req, UUID.randomUUID().toString(), 50, 0);
            }, "Expected IOException / HttpTimeoutException due to request timeout");
        }
    }

    @Test
    public void testGrpcRetryWithExponentialBackoff() {
        // Fail the first 2 requests, succeed on 3rd attempt
        core.setSimulatedFailures(2);

        try (TaskGrpcClient client = new TaskGrpcClient("localhost", GRPC_PORT)) {
            CreateTaskRequest req = CreateTaskRequest.newBuilder()
                    .setUserId(3001)
                    .setTitle("Retry Task")
                    .setIdempotencyKey(UUID.randomUUID().toString())
                    .build();

            // maxRetries = 3 -> Should successfully recover!
            CreateTaskResponse resp = client.createTaskWithRetry(req, 1000, 3);
            assertNotNull(resp);
            assertNotNull(resp.getTask());
            assertEquals("Retry Task", resp.getTask().getTitle());
        }
    }

    @Test
    public void testRestRetryWithExponentialBackoff() throws Exception {
        // Fail the first 2 requests, succeed on 3rd attempt
        core.setSimulatedFailures(2);

        try (TaskRestClient client = new TaskRestClient("localhost", REST_PORT)) {
            CreateTaskDto req = new CreateTaskDto(3001, "Retry REST Task", "Desc", 1, 0);

            // maxRetries = 3 -> Should successfully recover!
            TaskResponseDto resp = client.createTaskWithRetry(req, UUID.randomUUID().toString(), 1000, 3);
            assertNotNull(resp);
            assertNotNull(resp.getTask());
            assertEquals("Retry REST Task", resp.getTask().getTitle());
        }
    }
}
