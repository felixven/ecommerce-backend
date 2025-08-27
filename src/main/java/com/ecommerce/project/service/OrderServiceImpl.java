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
            // 成交單價（通常是 specialPrice / 購物車已算好的價）
            double unitPaid = cartItem.getProductPrice();
            // 每件折扣金額 = 原價 - 成交價（避免負值）
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

    // ===================================================
    // 2) Line Pay：帶 orderId，把「預訂單」落袋（新路徑）
    // ===================================================
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
        // 沒帶 orderId：回退到你原本的 Stripe 方法（完全不改它）
        if (orderId == null) {
            return placeOrder(emailId, addressId, paymentMethod, pgName, pgPaymentId, pgStatus, pgResponseMessage);
        }

        // 1) 撈出預訂單（Line Pay 先建的那筆）
        Order order = orderRepository.findByOrderIdAndEmail(orderId, emailId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "orderId", orderId));

        // 2) 地址處理 — 與 Stripe 一樣
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));
        order.setAddress(address);

        // 3) 準備品項：若預訂單沒有 items，就「完全比照 Stripe」用購物車轉成 OrderItem
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
            order.setTotalAmount(cart.getTotalPrice()); // 和 Stripe 一樣，總額來自購物車
        } else {
            itemsToUse = order.getOrderItems();
        }

        // 4) Payment — 寫法與 Stripe 一致，只是值來自 Line Pay
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

        // 5) 狀態字串與 Stripe 完全一致
        order.setOrderStatus("Order Accepted !");
        Order savedOrder = orderRepository.save(order);

        // 6) 扣庫存 — 與 Stripe 同步的寫法
        for (OrderItem item : itemsToUse) {
            Product product = item.getProduct();
            int newQty = product.getQuantity() - item.getQuantity();
            if (newQty < 0) throw new APIException("Insufficient stock for " + product.getProductId());
            product.setQuantity(newQty);
            productRepository.save(product);
        }

        // 7) 清購物車 — 與 Stripe 同步的寫法（存在才清）
        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart != null && cart.getCartItems() != null && !cart.getCartItems().isEmpty()) {
            for (CartItem ci : new ArrayList<>(cart.getCartItems())) {
                cartService.deleteProductFromCart(cart.getCartId(), ci.getProduct().getProductId());
            }
        }

        // 8) 回傳 DTO — 風格保持和你 Stripe 版一致（你用 modelMapper 的話就照用）
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
            Double totalAmount,             // 可帶也可不帶（預設用後端實算，避免金額不一致）
            List<OrderItemDTO> orderItems   // 可為 null/空：表示用「整個購物車」建立預訂單
    ) {
        // 1) 讀購物車（Line Pay 我們也沿用 Stripe 的邏輯來源：購物車的成交價/折扣）
        Cart cart = cartRepository.findCartByEmail(email);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", email);
        }

        // 2) 讀地址（必填）
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new RuntimeException("Address not found"));

        // 3) 先建立一筆「預訂單」（PENDING）
        //    ★ 這裡只是把購物車快照下來，先不扣庫存、不清購物車、不寫 Payment
        Order order = new Order();
        order.setEmail(email);
        order.setOrderDate(LocalDate.now());
        order.setOrderStatus("PENDING");
        order.setAddress(address);

        // 4) 組訂單明細（兩種模式）
        //    - A. 沒有帶 orderItems：用「整個購物車」建立預訂單
        //    - B. 有帶 orderItems：用「子集合」建立預訂單（成交價仍以購物車為準）
        List<OrderItem> orderItemList = new ArrayList<>();

        if (orderItems == null || orderItems.isEmpty()) {
            // ========== A. 全車模式 ==========
            if (cart.getCartItems() == null || cart.getCartItems().isEmpty()) {
                throw new RuntimeException("Cart is empty");
            }
            for (CartItem ci : cart.getCartItems()) {
                Product product = ci.getProduct();

                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProduct(product);
                item.setQuantity(ci.getQuantity());

                // ✅ 與 Stripe 一致：成交單價取購物車的 productPrice；折扣 = 原價 - 成交價（避免負值）
                double unitPaid = ci.getProductPrice();
                double unitDiscount = Math.max(0, product.getPrice() - unitPaid);
                item.setOrderedProductPrice(unitPaid);
                item.setDiscount(unitDiscount);

                orderItemList.add(item);
            }
        } else {
            // ========== B. 子集合模式 ==========
            // 前端傳進來要結帳的品項（仍需存在於購物車）
            orderItemList = orderItems.stream().map(dto -> {
                Long productId = dto.getProduct().getProductId();

                // 找商品
                Product product = productRepository.findById(productId)
                        .orElseThrow(() -> new RuntimeException("Product not found"));

                // 必須存在於購物車才允許結帳
                CartItem cartItem = cart.getCartItems().stream()
                        .filter(ci -> ci.getProduct().getProductId().equals(productId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("CartItem not found"));

                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProduct(product);
                item.setQuantity(dto.getQuantity());

                // ✅ 與 Stripe 一致：成交單價/折扣以「購物車」為準，不信任前端價格
                double unitPaid = cartItem.getProductPrice();
                double unitDiscount = Math.max(0, product.getPrice() - unitPaid);
                item.setOrderedProductPrice(unitPaid);
                item.setDiscount(unitDiscount);

                return item;
            }).collect(Collectors.toList());
        }

        // 掛上明細
        order.setOrderItems(orderItemList);

        // 5) 計算總額（一次性計算，避免在 lambda 內累加造成「需為 final」錯誤）
        double computedTotal = orderItemList.stream()
                .mapToDouble(oi -> oi.getOrderedProductPrice() * oi.getQuantity())
                .sum();

        // ✅ 金額以後端實算為準，避免與前端/Line Pay reserve 金額不一致
        //    若一定要信任前端，可改：totalAmount != null ? totalAmount : computedTotal
        order.setTotalAmount(computedTotal);

        // 6) 儲存預訂單（PENDING），僅保存結帳快照
        Order savedOrder = orderRepository.save(order);

        // 手工組 DTO（保留你的風格）
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