package kz.tasktracker.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateTaskDto {
    @JsonProperty("user_id")
    private long userId;
    private String title;
    private String description;
    private int priority;
    private long deadline;

    public CreateTaskDto() {}

    public CreateTaskDto(long userId, String title, String description, int priority, long deadline) {
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.deadline = deadline;
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public long getDeadline() { return deadline; }
    public void setDeadline(long deadline) { this.deadline = deadline; }
}
