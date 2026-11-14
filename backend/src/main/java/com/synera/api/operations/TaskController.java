package com.synera.api.operations;

import com.synera.api.catalog.ApiPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.OffsetDateTime;
import java.util.*;

@RestController @Validated @RequestMapping("/api/v1") @SecurityRequirement(name="bearerAuth")
public class TaskController {
    private final TaskService tasks;public TaskController(TaskService tasks){this.tasks=tasks;}
    @GetMapping("/tasks") public ApiPage<TaskService.TaskView> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="25") @Min(1) @Max(100) int size){return tasks.list(jwt,status,page,size);}
    @PostMapping("/tasks") public TaskService.TaskView create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateTask body){return tasks.create(jwt,body.title(),body.description(),body.assigneeId(),body.dueAt());}
    @PostMapping("/tasks/{id}/assign") public Map<String,Integer> assign(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Assignment body){return Map.of("updated",tasks.assign(jwt,id,body.assigneeId(),body.version()));}
    @PostMapping("/tasks/{id}/complete") public Map<String,Integer> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Version body){return Map.of("updated",tasks.finish(jwt,id,body.version()));}
    @PutMapping("/interactions/{id}/follow-up") public Map<String,Integer> followUp(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody FollowUp body){return Map.of("updated",tasks.followUp(jwt,id,body.dueAt()));}
    public record CreateTask(@NotBlank @Size(max=240) String title,@Size(max=5000) String description,UUID assigneeId,@NotNull OffsetDateTime dueAt){}
    public record Assignment(@NotNull UUID assigneeId,@Min(0) long version){}
    public record Version(@Min(0) long version){}
    public record FollowUp(OffsetDateTime dueAt){}
}
