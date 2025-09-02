package com.ecommerce.project.controller;

import com.ecommerce.project.model.User;
import com.ecommerce.project.payload.*;
import com.ecommerce.project.service.LinePayService;
import com.ecommerce.project.service.OrderService;
import com.ecommerce.project.service.StripeService;
import com.ecommerce.project.util.AuthUtil;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private StripeService stripeService;

    @Autowired
    private LinePayService linePayService;

    @GetMapping("/users/orders")
    public ResponseEntity<List<OrderDTO>> getUserOrders() {
        User user = authUtil.loggedInUser();
        List<OrderDTO> orders = orderService.getOrdersByUserEmail(user.getEmail());
        return new ResponseEntity<>(orders, HttpStatus.OK);
    }


    @PostMapping("/order/users/payments/{paymentMethod}")
    public ResponseEntity<OrderDTO> orderProducts(
            @PathVariable String paymentMethod,
            @RequestBody OrderRequestDTO orderRequestDTO
    ) {
        String emailId = authUtil.loggedInEmail();

        OrderDTO order = orderService.placeOrder(
                emailId,
                orderRequestDTO.getAddressId(),
                paymentMethod,
                orderRequestDTO.getPgName(),
                orderRequestDTO.getPgPaymentId(),
                orderRequestDTO.getPgStatus(),
                orderRequestDTO.getPgResponseMessage(),
                orderRequestDTO.getOrderId()
        );

        return new ResponseEntity<>(order, HttpStatus.CREATED);
    }


    @PostMapping("/order/stripe-client-secret")
    public ResponseEntity<String> createStripeClientSecret(@RequestBody StripePaymentDTO stripePaymentDTO) throws StripeException {
        PaymentIntent paymentIntent=stripeService.paymentIntent(stripePaymentDTO);
        return  new ResponseEntity<>(paymentIntent.getClientSecret(),HttpStatus.CREATED);
    }

    @PostMapping("/order/linepay-reserve")
    public ResponseEntity<String> reserveLinePay(@RequestBody LinePayRequestDTO requestDTO) {
        String paymentUrl = linePayService.reserve(requestDTO);
        return new ResponseEntity<>(paymentUrl, HttpStatus.CREATED);
    }

    @PostMapping("/order/linepay-confirm/{transactionId}")
    public ResponseEntity<String> confirmLinePay(
            @PathVariable String transactionId,
            @RequestBody LinePayConfirmDTO confirmDTO
    ) {
        String result = linePayService.confirmPayment(transactionId, confirmDTO);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping("/order/create-for-linepay")
    public ResponseEntity<OrderDTO> createOrderForLinePay(@RequestBody OrderRequestDTO dto) {
        String emailId = authUtil.loggedInEmail();
        OrderDTO order = orderService.createOrderBeforeLinePay(
                emailId,
                dto.getAddressId(),
                dto.getTotalAmount(),
                dto.getOrderItems()
        );
        return new ResponseEntity<>(order, HttpStatus.CREATED);
    }

}