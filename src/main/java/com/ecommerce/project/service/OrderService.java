package com.ecommerce.project.service;

import com.ecommerce.project.payload.OrderDTO;
import com.ecommerce.project.payload.OrderItemDTO;
import jakarta.transaction.Transactional;

import java.util.List;

public interface OrderService {
    // Stripe
    @Transactional
    OrderDTO placeOrder(
            String emailId,
            Long addressId,
            String paymentMethod,
            String pgName,
            String pgPaymentId,
            String pgStatus,
            String pgResponseMessage
    );

    // Line Pay
    @Transactional
    OrderDTO placeOrder(
            String emailId,
            Long addressId,
            String paymentMethod,
            String pgName,
            String pgPaymentId,
            String pgStatus,
            String pgResponseMessage,
            Long orderId
    );

    OrderDTO createOrderBeforeLinePay(String emailId, Long addressId, Double totalAmount, List<OrderItemDTO> orderItems);

    List<OrderDTO> getOrdersByUserEmail(String email);
}