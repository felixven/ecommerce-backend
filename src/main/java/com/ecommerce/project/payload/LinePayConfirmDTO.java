package com.ecommerce.project.payload;

import lombok.Data;

@Data
public class LinePayConfirmDTO {
        private Long orderId;
        private String pgName;
        private String pgPaymentId;
        private String pgStatus;
        private String pgResponseMessage;
        private int amount;
        private String currency;
}
