package com.synera.api.operations;

import com.synera.api.catalog.ApiPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import java.util.UUID;

@RestController @Validated @RequestMapping("/api/v1/notifications") @SecurityRequirement(name="bearerAuth")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications){this.notifications=notifications;}
    @GetMapping public ApiPage<NotificationService.Notice> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="25") @Min(1) @Max(100) int size){return notifications.list(jwt,unreadOnly,page,size);}
    @PostMapping("/{id}/read") public Map<String,Integer> read(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return Map.of("updated",notifications.read(jwt,id));}
    @PostMapping("/read-all") public Map<String,Integer> readAll(@AuthenticationPrincipal Jwt jwt){return Map.of("updated",notifications.readAll(jwt));}
    @PostMapping("/devices") public Map<String,UUID> device(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody DeviceRequest body){return Map.of("id",notifications.register(jwt,body.deviceId(),body.expoToken()));}
    @DeleteMapping("/devices/{deviceId}") public Map<String,Integer> removeDevice(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=160) String deviceId){return Map.of("updated",notifications.unregister(jwt,deviceId));}
    public record DeviceRequest(@NotBlank @Size(max=160) String deviceId,@NotBlank @Size(max=240) String expoToken){}
}
