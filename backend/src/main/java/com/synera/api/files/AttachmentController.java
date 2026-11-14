package com.synera.api.files;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;

@RestController @Validated @RequestMapping("/api/v1/attachments") @SecurityRequirement(name="bearerAuth")
public class AttachmentController {
    private final AttachmentService files;public AttachmentController(AttachmentService files){this.files=files;}
    @GetMapping("/options") public Map<String,Object> options(@AuthenticationPrincipal Jwt jwt){return files.options(jwt);}
    @PostMapping("/presign") public Map<String,Object> prepare(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody UploadRequest body){return files.prepare(jwt,body.ownerType(),body.ownerId(),body.fileName(),body.contentType(),body.sizeBytes());}
    @PostMapping("/{id}/complete") public Map<String,Object> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return files.complete(jwt,id);}
    @GetMapping("/{id}/download") public Map<String,Object> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="false") boolean thumbnail){return files.download(jwt,id,thumbnail);}
    @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam String ownerType,@RequestParam UUID ownerId){return files.list(jwt,ownerType,ownerId);}
    public record UploadRequest(@NotBlank String ownerType,@NotNull UUID ownerId,@NotBlank @Size(max=255) String fileName,@NotBlank String contentType,@Min(1) long sizeBytes){}
}
