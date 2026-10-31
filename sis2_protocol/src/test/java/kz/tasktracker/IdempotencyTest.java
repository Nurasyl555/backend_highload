package kz.tasktracker;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class IdempotencyTest {
    private static final int REST_PORT = 18080;
    private static final int GRPC_PORT = 15051;

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
    public void testRestIdempotency() throws Exception {
        try (TaskRestClient client = new TaskRestClient("localhost", REST_PORT)) {
            String idempotencyKey = UUID.randomUUID().toString();
            CreateTaskDto req = new CreateTaskDto(1001, "Test REST Idempotency", "Description", 1, System.currentTimeMillis() + 10000);

            // 1st attempt
            TaskResponseDto resp1 = client.createTaskWithRetry(req, idempotencyKey, 1000, 2);
            assertNotNull(resp1.getTask());
            assertFalse(resp1.isDuplicate(), "First attempt should not be a duplicate");
            long taskId = resp1.getTask().getId();

            // 2nd attempt with same key
            TaskResponseDto resp2 = client.createTaskWithRetry(req, idempotencyKey, 1000, 2);
            assertNotNull(resp2.getTask());
            assertTrue(resp2.isDuplicate(), "Second attempt with same key must return duplicate flag");
            assertEquals(taskId, resp2.getTask().getId(), "Must return identical task ID");

            // Total tasks in store must be exactly 1
            assertEquals(1, core.getTaskCount(), "Store must contain only 1 task despite repeated calls");
        }
    }

    @Test
    public void testGrpcIdempotency() {
        try (TaskGrpcClient client = new TaskGrpcClient("localhost", GRPC_PORT)) {
            String idempotencyKey = UUID.randomUUID().toString();
            CreateTaskRequest req = CreateTaskRequest.newBuilder()
                    .setUserId(1001)
                    .setTitle("Test gRPC Idempotency")
                    .setDescription("Description")
                    .setPriority(TaskPriority.HIGH)
                    .setDeadline(System.currentTimeMillis() + 10000)
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            // 1st attempt
            CreateTaskResponse resp1 = client.createTaskWithRetry(req, 1000, 2);
            assertNotNull(resp1.getTask());
            assertFalse(resp1.getIsDuplicate(), "First attempt should not be a duplicate");
            long taskId = resp1.getTask().getId();

            // 2nd attempt with same key
            CreateTaskResponse resp2 = client.createTaskWithRetry(req, 1000, 2);
            assertNotNull(resp2.getTask());
            assertTrue(resp2.getIsDuplicate(), "Second attempt must return duplicate flag");
            assertEquals(taskId, resp2.getTask().getId(), "Must return identical task ID");

            // Total tasks in store must be exactly 1
            assertEquals(1, core.getTaskCount(), "Store must contain only 1 task despite repeated calls");
        }
    }
}
