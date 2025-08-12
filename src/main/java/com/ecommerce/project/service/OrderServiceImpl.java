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

        //改了這裡

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
            orderItem.setDiscount(cartItem.getDiscount());
            orderItem.setOrderedProductPrice(cartItem.getProductPrice());
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
    public OrderDTO createOrderBeforeLinePay(String email, Long addressId, Double totalAmount, List<OrderItemDTO> orderItems) {

        // 從購物車計算折扣後金額
        Cart cart = cartRepository.findCartByEmail(email);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", email);
        }
        double finalTotal = cart.getTotalPrice();


        // 建立 Order
        Order order = new Order();
        order.setEmail(email);
        order.setOrderDate(LocalDate.now());
        order.setTotalAmount(finalTotal);
        order.setOrderStatus("PENDING");

        // 設定 Address
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new RuntimeException("Address not found"));
        order.setAddress(address);

        // 建立 OrderItem 實體清單
        List<OrderItem> orderItemList = orderItems.stream().map(dto -> {
            OrderItem item = new OrderItem();
            item.setQuantity(dto.getQuantity());

            Product product = productRepository.findById(dto.getProduct().getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));
            item.setProduct(product);
            item.setOrder(order);

            // 從購物車找到相同商品
            CartItem cartItem = cart.getCartItems().stream()
                    .filter(ci -> ci.getProduct().getProductId().equals(product.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("CartItem not found"));

            // ✅ 關鍵兩行：來源改為與 Stripe 相同
            item.setDiscount(cartItem.getDiscount());
            item.setOrderedProductPrice(cartItem.getProductPrice());

            return item;
        }).toList();

        order.setOrderItems(orderItemList);


        // 儲存到資料庫
        Order savedOrder = orderRepository.save(order);

        // 手動建立 DTO 回傳
        OrderDTO dto = new OrderDTO();
        dto.setOrderId(savedOrder.getOrderId());
        dto.setEmail(savedOrder.getEmail());
        dto.setOrderDate(savedOrder.getOrderDate());
        dto.setTotalAmount(savedOrder.getTotalAmount());
        dto.setOrderStatus(savedOrder.getOrderStatus());
        dto.setAddressId(savedOrder.getAddress().getAddressId());

        // 把 OrderItems 轉成 DTO
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