package kz.tasktracker.grpc;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import kz.tasktracker.model.TaskDto;
import kz.tasktracker.proto.*;
import kz.tasktracker.service.TaskServiceCore;

import java.util.List;

public class TaskGrpcServiceImpl extends TaskGrpcServiceGrpc.TaskGrpcServiceImplBase {
    private final TaskServiceCore core;

    public TaskGrpcServiceImpl(TaskServiceCore core) {
        this.core = core;
    }

    @Override
    public void createTask(CreateTaskRequest request, StreamObserver<CreateTaskResponse> responseObserver) {
        if (Context.current().isCancelled()) {
            responseObserver.onError(Status.CANCELLED.withDescription("Request deadline exceeded").asRuntimeException());
            return;
        }

        try {
            boolean[] isDuplicate = new boolean[1];
            TaskDto dto = core.createTask(
                    request.getIdempotencyKey(),
                    request.getUserId(),
                    request.getTitle(),
                    request.getDescription(),
                    request.getPriorityValue(),
                    request.getDeadline(),
                    isDuplicate
            );

            Task protoTask = toProto(dto);
            CreateTaskResponse response = CreateTaskResponse.newBuilder()
                    .setTask(protoTask)
                    .setIsDuplicate(isDuplicate[0])
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getTask(GetTaskRequest request, StreamObserver<GetTaskResponse> responseObserver) {
        if (Context.current().isCancelled()) {
            responseObserver.onError(Status.CANCELLED.withDescription("Request deadline exceeded").asRuntimeException());
            return;
        }

        try {
            TaskDto dto = core.getTask(request.getId());
            if (dto == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Task not found").asRuntimeException());
                return;
            }

            GetTaskResponse response = GetTaskResponse.newBuilder()
                    .setTask(toProto(dto))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listTasks(ListTasksRequest request, StreamObserver<ListTasksResponse> responseObserver) {
        if (Context.current().isCancelled()) {
            responseObserver.onError(Status.CANCELLED.withDescription("Request deadline exceeded").asRuntimeException());
            return;
        }

        try {
            List<TaskDto> dtos = core.listTasks(request.getUserId(), request.getLimit());
            ListTasksResponse.Builder builder = ListTasksResponse.newBuilder();
            for (TaskDto dto : dtos) {
                builder.addTasks(toProto(dto));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    public static Task toProto(TaskDto dto) {
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(dto.getStatus());
        } catch (Exception e) {
            status = TaskStatus.TODO;
        }

        TaskPriority priority = TaskPriority.forNumber(dto.getPriority());
        if (priority == null) priority = TaskPriority.MEDIUM;

        return Task.newBuilder()
                .setId(dto.getId())
                .setUserId(dto.getUserId())
                .setTitle(dto.getTitle() != null ? dto.getTitle() : "")
                .setDescription(dto.getDescription() != null ? dto.getDescription() : "")
                .setStatus(status)
                .setPriority(priority)
                .setDeadline(dto.getDeadline())
                .setVersion(dto.getVersion())
                .setCreatedAt(dto.getCreatedAt())
                .setUpdatedAt(dto.getUpdatedAt())
                .build();
    }
}
