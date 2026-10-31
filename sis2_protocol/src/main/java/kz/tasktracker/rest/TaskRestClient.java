package kz.tasktracker.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.tasktracker.model.CreateTaskDto;
import kz.tasktracker.model.TaskDto;
import kz.tasktracker.model.TaskResponseDto;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class TaskRestClient implements AutoCloseable {
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskRestClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/api/v1/tasks";
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public TaskResponseDto createTaskWithRetry(CreateTaskDto dto, String idempotencyKey, long timeoutMs, int maxRetries)
            throws IOException, InterruptedException {
        int attempt = 0;
        long baseBackoffMs = 20;
        byte[] body = mapper.writeValueAsBytes(dto);

        while (true) {
            attempt++;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                reqBuilder.header("Idempotency-Key", idempotencyKey);
            }

            try {
                HttpResponse<byte[]> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 201) {
                    return mapper.readValue(response.body(), TaskResponseDto.class);
                }

                // If transient 503 error and idempotent request, retry
                boolean isTransient = (status == 503);
                boolean canRetry = isTransient && idempotencyKey != null && attempt <= maxRetries;
                if (!canRetry) {
                    throw new IOException("HTTP error: " + status + " - " + new String(response.body()));
                }
            } catch (IOException e) {
                boolean canRetry = idempotencyKey != null && attempt <= maxRetries;
                if (!canRetry) {
                    throw e;
                }
            }

            // Exponential Backoff with Full Jitter
            long maxSleep = (long) (baseBackoffMs * Math.pow(2, attempt - 1));
            long sleepMs = ThreadLocalRandom.current().nextLong(maxSleep / 2, maxSleep + 1);
            Thread.sleep(sleepMs);
        }
    }

    public TaskDto getTask(long id, long timeoutMs) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + id))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();

        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP error: " + resp.statusCode());
        }
        return mapper.readValue(resp.body(), TaskDto.class);
    }

    public List<TaskDto> listTasks(long userId, int limit, long timeoutMs) throws IOException, InterruptedException {
        String url = baseUrl + "?userId=" + userId + "&limit=" + limit;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();

        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP error: " + resp.statusCode());
        }
        return mapper.readValue(resp.body(), new TypeReference<List<TaskDto>>() {});
    }

    @Override
    public void close() {
        // HttpClient handles connection pool automatically
    }
}
