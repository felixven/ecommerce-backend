package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.payload.OrderDTO;
import com.ecommerce.project.payload.OrderItemDTO;
import com.ecommerce.project.payload.ProductDTO;
import com.ecommerce.project.repositories.*;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    CartRepository cartRepository;

    @Autowired
    AddressRepository addressRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    CartService cartService;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    ProductRepository productRepository;

    @Override
    @Transactional
    public OrderDTO placeOrder(String emailId, Long addressId, String paymentMethod, String pgName, String pgPaymentId, String pgStatus, String pgResponseMessage) {

        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", emailId);
        }

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));

        Order order = new Order();
        order.setEmail(emailId);
        order.setOrderDate(LocalDate.now());
        order.setTotalAmount(cart.getTotalPrice());
        order.setOrderStatus("Order Accepted !");
        order.setAddress(address);

        Payment payment = new Payment(paymentMethod, pgPaymentId, pgStatus, pgResponseMessage, pgName);
        payment.setOrder(order);
        payment = paymentRepository.save(payment);
        order.setPayment(payment);

        Order savedOrder = orderRepository.save(order);

        List<CartItem> cartItems = cart.getCartItems();
        if (cartItems.isEmpty()) {
            throw new APIException("Cart is empty");
        }

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setQuantity(cartItem.getQuantity());
            double unitPaid = cartItem.getProductPrice();
            double unitDiscount = Math.max(0, cartItem.getProduct().getPrice() - unitPaid);
            orderItem.setOrderedProductPrice(unitPaid);
            orderItem.setDiscount(unitDiscount);
            orderItem.setOrder(savedOrder);
            orderItems.add(orderItem);
        }

        orderItems = orderItemRepository.saveAll(orderItems);

        cart.getCartItems().forEach(item -> {
            int quantity = item.getQuantity();
            Product product = item.getProduct();

            // Reduce stock quantity
            product.setQuantity(product.getQuantity() - quantity);

            // Save product back to the database
            productRepository.save(product);

            // Remove items from cart
            cartService.deleteProductFromCart(cart.getCartId(), item.getProduct().getProductId());
        });

        OrderDTO orderDTO = modelMapper.map(savedOrder, OrderDTO.class);
        orderItems.forEach(item -> orderDTO.getOrderItems().add(modelMapper.map(item, OrderItemDTO.class)));

        orderDTO.setAddressId(addressId);

        return orderDTO;
    }

    @Override
    @Transactional
    public OrderDTO placeOrder(
            String emailId,
            Long addressId,
            String paymentMethod,
            String pgName,
            String pgPaymentId,
            String pgStatus,
            String pgResponseMessage,
            Long orderId
    ) {
        if (orderId == null) {
            return placeOrder(emailId, addressId, paymentMethod, pgName, pgPaymentId, pgStatus, pgResponseMessage);
        }

        Order order = orderRepository.findByOrderIdAndEmail(orderId, emailId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "orderId", orderId));

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));
        order.setAddress(address);
        List<OrderItem> itemsToUse;
        if (order.getOrderItems() == null || order.getOrderItems().isEmpty()) {
            Cart cart = cartRepository.findCartByEmail(emailId);
            if (cart == null) throw new ResourceNotFoundException("Cart", "email", emailId);

            List<CartItem> cartItems = cart.getCartItems();
            if (cartItems == null || cartItems.isEmpty()) throw new APIException("Cart is empty");

            List<OrderItem> newOrderItems = new ArrayList<>();
            for (CartItem ci : cartItems) {
                OrderItem oi = new OrderItem();
                oi.setProduct(ci.getProduct());
                oi.setQuantity(ci.getQuantity());
                double unitPaid = ci.getProductPrice();
                double unitDiscount = Math.max(0, ci.getProduct().getPrice() - unitPaid);
                oi.setOrderedProductPrice(unitPaid);
                oi.setDiscount(unitDiscount);
                oi.setOrder(order);
                newOrderItems.add(oi);
            }
            itemsToUse = orderItemRepository.saveAll(newOrderItems);
            order.setOrderItems(itemsToUse);
            order.setTotalAmount(cart.getTotalPrice());
        } else {
            itemsToUse = order.getOrderItems();
        }

        Payment payment = order.getPayment();
        if (payment == null) {
            payment = new Payment(paymentMethod, pgPaymentId, pgStatus, pgResponseMessage, pgName);
        } else {
            payment.setPaymentMethod(paymentMethod);
            payment.setPgPaymentId(pgPaymentId);
            payment.setPgStatus(pgStatus);
            payment.setPgResponseMessage(pgResponseMessage);
            payment.setPgName(pgName);
        }
        payment.setOrder(order);
        payment = paymentRepository.save(payment);
        order.setPayment(payment);
        order.setOrderStatus("Order Accepted !");
        Order savedOrder = orderRepository.save(order);

        for (OrderItem item : itemsToUse) {
            Product product = item.getProduct();
            int newQty = product.getQuantity() - item.getQuantity();
            if (newQty < 0) throw new APIException("Insufficient stock for " + product.getProductId());
            product.setQuantity(newQty);
            productRepository.save(product);
        }

        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart != null && cart.getCartItems() != null && !cart.getCartItems().isEmpty()) {
            for (CartItem ci : new ArrayList<>(cart.getCartItems())) {
                cartService.deleteProductFromCart(cart.getCartId(), ci.getProduct().getProductId());
            }
        }

        OrderDTO orderDTO = modelMapper.map(savedOrder, OrderDTO.class);
        for (OrderItem oi : itemsToUse) {
            orderDTO.getOrderItems().add(modelMapper.map(oi, OrderItemDTO.class));
        }
        orderDTO.setAddressId(addressId);
        return orderDTO;
    }

    @Override
    @Transactional
    public OrderDTO createOrderBeforeLinePay(
            String email,
            Long addressId,
            Double totalAmount,
            List<OrderItemDTO> orderItems
    ) {
        Cart cart = cartRepository.findCartByEmail(email);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", email);
        }

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new RuntimeException("Address not found"));

        Order order = new Order();
        order.setEmail(email);
        order.setOrderDate(LocalDate.now());
        order.setOrderStatus("PENDING");
        order.setAddress(address);

        List<OrderItem> orderItemList = new ArrayList<>();

        if (orderItems == null || orderItems.isEmpty()) {
            if (cart.getCartItems() == null || cart.getCartItems().isEmpty()) {
                throw new RuntimeException("Cart is empty");
            }
            for (CartItem ci : cart.getCartItems()) {
                Product product = ci.getProduct();

                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProduct(product);
                item.setQuantity(ci.getQuantity());
                double unitPaid = ci.getProductPrice();
                double unitDiscount = Math.max(0, product.getPrice() - unitPaid);
                item.setOrderedProductPrice(unitPaid);
                item.setDiscount(unitDiscount);

                orderItemList.add(item);
            }
        } else {
            orderItemList = orderItems.stream().map(dto -> {
                Long productId = dto.getProduct().getProductId();

                Product product = productRepository.findById(productId)
                        .orElseThrow(() -> new RuntimeException("Product not found"));

                CartItem cartItem = cart.getCartItems().stream()
                        .filter(ci -> ci.getProduct().getProductId().equals(productId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("CartItem not found"));

                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProduct(product);
                item.setQuantity(dto.getQuantity());
                double unitPaid = cartItem.getProductPrice();
                double unitDiscount = Math.max(0, product.getPrice() - unitPaid);
                item.setOrderedProductPrice(unitPaid);
                item.setDiscount(unitDiscount);

                return item;
            }).collect(Collectors.toList());
        }

        order.setOrderItems(orderItemList);
        double computedTotal = orderItemList.stream()
                .mapToDouble(oi -> oi.getOrderedProductPrice() * oi.getQuantity())
                .sum();
        order.setTotalAmount(computedTotal);
        Order savedOrder = orderRepository.save(order);
        OrderDTO dto = new OrderDTO();
        dto.setOrderId(savedOrder.getOrderId());
        dto.setEmail(savedOrder.getEmail());
        dto.setOrderDate(savedOrder.getOrderDate());
        dto.setTotalAmount(savedOrder.getTotalAmount());
        dto.setOrderStatus(savedOrder.getOrderStatus());
        dto.setAddressId(savedOrder.getAddress().getAddressId());

        List<OrderItemDTO> itemDTOs = savedOrder.getOrderItems().stream().map(item -> {
            OrderItemDTO itemDTO = new OrderItemDTO();
            itemDTO.setOrderItemId(item.getOrderItemId());
            itemDTO.setQuantity(item.getQuantity());
            itemDTO.setDiscount(item.getDiscount());
            itemDTO.setOrderedProductPrice(item.getOrderedProductPrice());

            ProductDTO productDTO = new ProductDTO();
            productDTO.setProductId(item.getProduct().getProductId());
            productDTO.setProductName(item.getProduct().getProductName());
            productDTO.setImage(item.getProduct().getImage());
            productDTO.setDescription(item.getProduct().getDescription());
            productDTO.setPrice(item.getProduct().getPrice());
            productDTO.setDiscount(item.getProduct().getDiscount());
            productDTO.setSpecialPrice(item.getProduct().getSpecialPrice());

            itemDTO.setProduct(productDTO);
            return itemDTO;
        }).collect(Collectors.toList());

        dto.setOrderItems(itemDTOs);
        return dto;
    }


    @Override
    public List<OrderDTO> getOrdersByUserEmail(String email) {
        List<Order> orders = orderRepository.findByEmailOrderByOrderIdDesc(email);

        return orders.stream().map(order -> {
            OrderDTO dto = new OrderDTO();
            dto.setOrderId(order.getOrderId());
            dto.setEmail(order.getEmail());
            dto.setOrderDate(order.getOrderDate());
            dto.setTotalAmount(order.getTotalAmount());
            dto.setOrderStatus(order.getOrderStatus());
            dto.setAddressId(order.getAddress().getAddressId());

            List<OrderItemDTO> itemDTOs = order.getOrderItems().stream().map(item -> {
                OrderItemDTO itemDTO = new OrderItemDTO();
                itemDTO.setOrderItemId(item.getOrderItemId());
                itemDTO.setQuantity(item.getQuantity());
                itemDTO.setDiscount(item.getDiscount());
                itemDTO.setOrderedProductPrice(item.getOrderedProductPrice());

                ProductDTO productDTO = new ProductDTO();
                productDTO.setProductId(item.getProduct().getProductId());
                productDTO.setQuantity(item.getProduct().getQuantity());
                productDTO.setProductName(item.getProduct().getProductName());
                productDTO.setDescription(item.getProduct().getDescription());
                productDTO.setPrice(item.getProduct().getPrice());
                productDTO.setDiscount(item.getProduct().getDiscount());
                productDTO.setSpecialPrice(item.getProduct().getSpecialPrice());

                itemDTO.setProduct(productDTO);
                return itemDTO;
            }).collect(Collectors.toList());

            dto.setOrderItems(itemDTOs);
            return dto;
        }).collect(Collectors.toList());
    }

}