package com.ecommerce.project.payload;

import lombok.Data;

@Data
public class LinePayRequestDTO {
    private Long amount;
    private String currency;
    private String orderId;
    private String productName;
    private String confirmUrl;
    private String cancelUrl;
}
