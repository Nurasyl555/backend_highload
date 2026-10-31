package kz.tasktracker.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import kz.tasktracker.model.CreateTaskDto;
import kz.tasktracker.model.TaskDto;
import kz.tasktracker.model.TaskResponseDto;
import kz.tasktracker.service.TaskServiceCore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public class TaskHttpServer {
    private final int port;
    private final HttpServer server;
    private final TaskServiceCore core;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskHttpServer(int port, TaskServiceCore core) throws IOException {
        this.port = port;
        this.core = core;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // Using Java 21 Virtual Threads for non-blocking highload concurrency
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        this.server.createContext("/api/v1/tasks", new TasksHandler());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private class TasksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                URI uri = exchange.getRequestURI();
                String path = uri.getPath();

                if ("POST".equalsIgnoreCase(method)) {
                    handleCreateTask(exchange);
                } else if ("GET".equalsIgnoreCase(method)) {
                    if (path.matches(".*/tasks/\\d+")) {
                        handleGetTaskById(exchange, path);
                    } else {
                        handleListTasks(exchange, uri);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (IllegalStateException e) {
                // Injected transient failure
                byte[] err = e.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(503, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            } catch (Exception e) {
                byte[] err = ("Internal error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        }

        private void handleCreateTask(HttpExchange exchange) throws IOException {
            String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            InputStream is = exchange.getRequestBody();
            CreateTaskDto req = mapper.readValue(is, CreateTaskDto.class);

            boolean[] isDuplicate = new boolean[1];
            TaskDto created = core.createTask(
                    idempotencyKey,
                    req.getUserId(),
                    req.getTitle(),
                    req.getDescription(),
                    req.getPriority(),
                    req.getDeadline(),
                    isDuplicate
            );

            TaskResponseDto resp = new TaskResponseDto(created, isDuplicate[0]);
            byte[] bytes = mapper.writeValueAsBytes(resp);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void handleGetTaskById(HttpExchange exchange, String path) throws IOException {
            String idStr = path.substring(path.lastIndexOf('/') + 1);
            long id = Long.parseLong(idStr);

            TaskDto task = core.getTask(id);
            if (task == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = mapper.writeValueAsBytes(task);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void handleListTasks(HttpExchange exchange, URI uri) throws IOException {
            long userId = 0;
            int limit = 20;

            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        if ("userId".equalsIgnoreCase(pair[0])) {
                            userId = Long.parseLong(pair[1]);
                        } else if ("limit".equalsIgnoreCase(pair[0])) {
                            limit = Integer.parseInt(pair[1]);
                        }
                    }
                }
            }

            List<TaskDto> list = core.listTasks(userId, limit);
            byte[] bytes = mapper.writeValueAsBytes(list);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
