package kz.tasktracker.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import kz.tasktracker.service.TaskServiceCore;

import java.io.IOException;

public class TaskGrpcServer {
    private final int port;
    private final Server server;

    public TaskGrpcServer(int port, TaskServiceCore core) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new TaskGrpcServiceImpl(core))
                .build();
    }

    public void start() throws IOException {
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    public int getPort() {
        return server != null ? server.getPort() : port;
    }
}
