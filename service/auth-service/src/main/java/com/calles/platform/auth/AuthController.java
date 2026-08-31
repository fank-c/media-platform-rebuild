package com.calles.platform.auth;

import com.calles.platform.common.core.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.ok("auth-service");
    }
}
