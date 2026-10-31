package kz.tasktracker.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TaskResponseDto {
    private TaskDto task;
    @JsonProperty("is_duplicate")
    private boolean duplicate;

    public TaskResponseDto() {}

    public TaskResponseDto(TaskDto task, boolean duplicate) {
        this.task = task;
        this.duplicate = duplicate;
    }

    public TaskDto getTask() { return task; }
    public void setTask(TaskDto task) { this.task = task; }

    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
}
