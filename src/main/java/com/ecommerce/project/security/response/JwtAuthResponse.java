package com.ecommerce.project.security.response;

import lombok.Data;
import java.util.List;

@Data
public class JwtAuthResponse {
    private String token;
    private Long id;
    private String username;
    private List<String> roles;

    public JwtAuthResponse(String token, Long id, String username, List<String> roles) {
        this.token = token;
        this.id = id;
        this.username = username;
        this.roles = roles;
    }
}
