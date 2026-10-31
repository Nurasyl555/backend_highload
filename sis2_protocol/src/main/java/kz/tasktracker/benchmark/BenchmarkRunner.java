package kz.tasktracker.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.tasktracker.grpc.TaskGrpcClient;
import kz.tasktracker.grpc.TaskGrpcServer;
import kz.tasktracker.grpc.TaskGrpcServiceImpl;
import kz.tasktracker.model.CreateTaskDto;
import kz.tasktracker.model.TaskDto;
import kz.tasktracker.model.TaskResponseDto;
import kz.tasktracker.proto.CreateTaskRequest;
import kz.tasktracker.proto.CreateTaskResponse;
import kz.tasktracker.proto.ListTasksResponse;
import kz.tasktracker.proto.TaskPriority;
import kz.tasktracker.rest.TaskHttpServer;
import kz.tasktracker.rest.TaskRestClient;
import kz.tasktracker.service.TaskServiceCore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BenchmarkRunner {
    private static final int REST_PORT = 8080;
    private static final int GRPC_PORT = 50051;
    private static final int WARMUP_REQUESTS = 500;
    private static final int TOTAL_REQUESTS = 2000;
    private static final int THREADS = 10;

    public static void main(String[] args) throws Exception {
        TaskServiceCore core = new TaskServiceCore();

        TaskHttpServer httpServer = new TaskHttpServer(REST_PORT, core);
        httpServer.start();

        TaskGrpcServer grpcServer = new TaskGrpcServer(GRPC_PORT, core);
        grpcServer.start();

        System.out.println("Servers started: REST on :" + REST_PORT + ", gRPC on :" + GRPC_PORT);

        try {
            long[] restPayloads = measureRestPayloads();
            long[] grpcPayloads = measureGrpcPayloads();

            System.out.println("\n--- Payload Size Comparison (Bytes) ---");
            System.out.printf("CreateTask Request : REST/JSON = %4d B | gRPC/Protobuf = %4d B (-%.1f%%)%n",
                    restPayloads[0], grpcPayloads[0], 100.0 * (1.0 - (double) grpcPayloads[0] / restPayloads[0]));
            System.out.printf("Single Task Resp   : REST/JSON = %4d B | gRPC/Protobuf = %4d B (-%.1f%%)%n",
                    restPayloads[1], grpcPayloads[1], 100.0 * (1.0 - (double) grpcPayloads[1] / restPayloads[1]));
            System.out.printf("ListTasks (20) Resp: REST/JSON = %4d B | gRPC/Protobuf = %4d B (-%.1f%%)%n",
                    restPayloads[2], grpcPayloads[2], 100.0 * (1.0 - (double) grpcPayloads[2] / restPayloads[2]));

            // Warmup
            runRestBenchmark(WARMUP_REQUESTS, 4, new BenchmarkMetrics("Warmup REST"));
            runGrpcBenchmark(WARMUP_REQUESTS, 4, new BenchmarkMetrics("Warmup gRPC"));
            core.clear();

            // Actual benchmark
            BenchmarkMetrics restMetrics = new BenchmarkMetrics("REST/JSON");
            double restDuration = runRestBenchmark(TOTAL_REQUESTS, THREADS, restMetrics);
            restMetrics.calculate(restDuration, restPayloads[0], restPayloads[1], restPayloads[2]);

            BenchmarkMetrics grpcMetrics = new BenchmarkMetrics("gRPC (HTTP/2)");
            double grpcDuration = runGrpcBenchmark(TOTAL_REQUESTS, THREADS, grpcMetrics);
            grpcMetrics.calculate(grpcDuration, grpcPayloads[0], grpcPayloads[1], grpcPayloads[2]);

            printResults(restMetrics, grpcMetrics);
        } finally {
            httpServer.stop();
            grpcServer.stop();
        }
    }

    private static long[] measureRestPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        long deadline = 1793450000000L;
        long now = 1793400000000L;

        CreateTaskDto req = new CreateTaskDto(4217, "Complete SIS #2 implementation", "Measure latency and payload size against REST baseline", 1, deadline);
        byte[] reqBytes = mapper.writeValueAsBytes(req);

        TaskDto task = new TaskDto(1001, 4217, "Complete SIS #2 implementation", "Measure latency and payload size against REST baseline", "TODO", 1, deadline, 1, now, now);
        TaskResponseDto resp = new TaskResponseDto(task, false);
        byte[] respBytes = mapper.writeValueAsBytes(resp);

        List<TaskDto> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(task);
        }
        byte[] batchBytes = mapper.writeValueAsBytes(batch);

        return new long[]{reqBytes.length, respBytes.length, batchBytes.length};
    }

    private static long[] measureGrpcPayloads() {
        long deadline = 1793450000000L;
        long now = 1793400000000L;

        CreateTaskRequest req = CreateTaskRequest.newBuilder()
                .setUserId(4217)
                .setTitle("Complete SIS #2 implementation")
                .setDescription("Measure latency and payload size against REST baseline")
                .setPriority(TaskPriority.HIGH)
                .setDeadline(deadline)
                .build();
        byte[] reqBytes = req.toByteArray();

        TaskDto taskDto = new TaskDto(1001, 4217, "Complete SIS #2 implementation", "Measure latency and payload size against REST baseline", "TODO", 1, deadline, 1, now, now);
        CreateTaskResponse resp = CreateTaskResponse.newBuilder()
                .setTask(TaskGrpcServiceImpl.toProto(taskDto))
                .setIsDuplicate(false)
                .build();
        byte[] respBytes = resp.toByteArray();

        ListTasksResponse.Builder batchBuilder = ListTasksResponse.newBuilder();
        for (int i = 0; i < 20; i++) {
            batchBuilder.addTasks(TaskGrpcServiceImpl.toProto(taskDto));
        }
        byte[] batchBytes = batchBuilder.build().toByteArray();

        return new long[]{reqBytes.length, respBytes.length, batchBytes.length};
    }

    private static double runRestBenchmark(int totalRequests, int threads, BenchmarkMetrics metrics) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        long startTime = System.nanoTime();

        try (TaskRestClient client = new TaskRestClient("localhost", REST_PORT)) {
            for (int i = 0; i < totalRequests; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        CreateTaskDto dto = new CreateTaskDto(100 + (idx % 10), "Task " + idx, "Task description " + idx, 2, 1793450000000L);
                        String key = UUID.randomUUID().toString();

                        long t0 = System.nanoTime();
                        client.createTaskWithRetry(dto, key, 1000, 2);
                        long t1 = System.nanoTime();

                        metrics.recordLatency((t1 - t0) / 1_000_000.0);
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            pool.shutdown();
        }

        return (System.nanoTime() - startTime) / 1_000_000_000.0;
    }

    private static double runGrpcBenchmark(int totalRequests, int threads, BenchmarkMetrics metrics) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        long startTime = System.nanoTime();

        try (TaskGrpcClient client = new TaskGrpcClient("localhost", GRPC_PORT)) {
            for (int i = 0; i < totalRequests; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        CreateTaskRequest req = CreateTaskRequest.newBuilder()
                                .setUserId(100 + (idx % 10))
                                .setTitle("Task " + idx)
                                .setDescription("Task description " + idx)
                                .setPriority(TaskPriority.MEDIUM)
                                .setDeadline(1793450000000L)
                                .setIdempotencyKey(UUID.randomUUID().toString())
                                .build();

                        long t0 = System.nanoTime();
                        client.createTaskWithRetry(req, 1000, 2);
                        long t1 = System.nanoTime();

                        metrics.recordLatency((t1 - t0) / 1_000_000.0);
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            pool.shutdown();
        }

        return (System.nanoTime() - startTime) / 1_000_000_000.0;
    }

    private static void printResults(BenchmarkMetrics rest, BenchmarkMetrics grpc) {
        System.out.println("\n--- Latency & Throughput Benchmark (" + TOTAL_REQUESTS + " requests, " + THREADS + " threads) ---");
        System.out.printf("%-18s | %-15s | %-15s%n", "Metric", "REST/JSON", "gRPC (HTTP/2)");
        System.out.println("--------------------------------------------------------");
        System.out.printf("%-18s | %12.2f ms | %12.2f ms%n", "Avg Latency", rest.getAvg(), grpc.getAvg());
        System.out.printf("%-18s | %12.2f ms | %12.2f ms%n", "p50 Latency", rest.getP50(), grpc.getP50());
        System.out.printf("%-18s | %12.2f ms | %12.2f ms%n", "p95 Latency", rest.getP95(), grpc.getP95());
        System.out.printf("%-18s | %12.2f ms | %12.2f ms%n", "p99 Latency", rest.getP99(), grpc.getP99());
        System.out.printf("%-18s | %9.0f req/s | %9.0f req/s%n", "Throughput", rest.getThroughputRps(), grpc.getThroughputRps());
    }
}
