package com.synera.api.order;

import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1") @SecurityRequirement(name="bearerAuth")
public class ReplacementController {
    private final ReplacementService replacements;public ReplacementController(ReplacementService replacements){this.replacements=replacements;}
    @GetMapping("/orders/sell-out/{order}/returns") @RequirePermission("order_so:view")
    public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID order){return replacements.list(jwt,order);}
    @PostMapping("/returns/{id}/replacement-shipment") @RequirePermission("order_so:ship_update")
    public Map<String,Object> ship(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Action body,HttpServletRequest request){return replacements.advance(jwt,id,body.version(),body.reason(),false,request);}
    @PostMapping("/returns/{id}/replacement-receipt") @RequirePermission("order_so:receive_confirm")
    public Map<String,Object> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Action body,HttpServletRequest request){return replacements.advance(jwt,id,body.version(),body.reason(),true,request);}
    public record Action(@Min(0) long version,@NotBlank @Size(max=500) String reason){}
}
