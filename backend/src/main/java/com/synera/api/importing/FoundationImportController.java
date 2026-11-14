package com.synera.api.importing;

import com.synera.api.catalog.ApiPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/foundation-imports")
@SecurityRequirement(name="bearerAuth")
public class FoundationImportController {
    private final FoundationImportService imports;
    public FoundationImportController(FoundationImportService imports){this.imports=imports;}
    @GetMapping("/types")
    @Operation(summary="Các loại import được phép theo vai và tenant hiện tại")
    public List<FoundationImportService.TypeView> types(@AuthenticationPrincipal Jwt jwt){return imports.types(jwt);}
    @GetMapping(value="/{kind}/template",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(summary="Tải mẫu XLSX có dòng ví dụ theo sheet/cột Vinamilk")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind)throws IOException {
        ImportKind type=ImportKind.parse(kind);imports.requireType(jwt,type);
        byte[] bytes=new ClassPathResource("import-templates/"+type.name().toLowerCase(Locale.ROOT)+".xlsx").getContentAsByteArray();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=synera-"+type.name().toLowerCase(Locale.ROOT)+".xlsx").body(bytes);
    }
    @PostMapping(value="/{kind}/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Tải lên và xem trước; chưa ghi dữ liệu nghiệp vụ; mật khẩu được băm và không trả về")
    public FoundationImportService.BatchView preview(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,
            @RequestParam MultipartFile file,@RequestParam @NotBlank @Size(max=500) String reason,HttpServletRequest request) {
        return imports.preview(jwt,ImportKind.parse(kind),file,reason,request);
    }
    @GetMapping("/batches")
    public ApiPage<FoundationImportService.BatchView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="25") @Min(1) @Max(100) int size){return imports.list(jwt,page,size);}
    @GetMapping("/batches/{id}")
    public FoundationImportService.BatchView get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return imports.get(jwt,id);}
    @GetMapping("/batches/{id}/rows")
    public ApiPage<FoundationImportService.RowView> rows(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @RequestParam(defaultValue="false") boolean errorsOnly,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int size){return imports.rows(jwt,id,errorsOnly,page,size);}
    @PostMapping("/batches/{id}/commit")
    @Operation(summary="Ghi toàn bộ lô trong một giao dịch; có lỗi thì không ghi; gọi lại an toàn")
    public FoundationImportService.BatchView commit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,HttpServletRequest request){return imports.commit(jwt,id,request);}
    @PostMapping("/batches/{id}/submit-credit")
    @Operation(summary="NSX_ADMIN gửi hạn mức nháp do chính mình nhập sang chờ duyệt; không tự duyệt")
    public java.util.Map<String,Integer> submitCredit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,HttpServletRequest request){return imports.submitCredit(jwt,id,request);}
}
