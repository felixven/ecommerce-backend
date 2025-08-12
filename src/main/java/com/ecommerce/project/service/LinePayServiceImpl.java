package com.ecommerce.project.service;

import com.ecommerce.project.model.*;
import com.ecommerce.project.payload.LinePayConfirmDTO;
import com.ecommerce.project.payload.LinePayRequestDTO;

// LinePayServiceImpl.java

import com.ecommerce.project.repositories.CartRepository;
import com.ecommerce.project.repositories.OrderRepository;
import com.ecommerce.project.repositories.PaymentRepository;
import com.ecommerce.project.repositories.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinePayServiceImpl implements LinePayService {

    @Value("${linepay.channel.id}")
    private String channelId;

    @Value("${linepay.channel.secret}")
    private String channelSecret;

    @Value("${linepay.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private ProductRepository productRepository;

    @Override
    public String reserve(LinePayRequestDTO requestDTO) {
        String endpoint = "/v3/payments/request";
        String requestUrl = apiUrl + endpoint;

        Map<String, Object> body = new HashMap<>();
        body.put("amount", requestDTO.getAmount());
        body.put("currency", requestDTO.getCurrency());
        body.put("orderId", requestDTO.getOrderId());
        body.put("packages", List.of(Map.of(
                "id", "package-1",
                "amount", requestDTO.getAmount(),
                "name", "Demo Package",
                "products", List.of(Map.of(
                        "id", "product-1",
                        "name", requestDTO.getProductName(),
                        "quantity", 1,
                        "price", requestDTO.getAmount()
                ))
        )));
        body.put("redirectUrls", Map.of(
                "confirmUrl", requestDTO.getConfirmUrl(),
                "cancelUrl", requestDTO.getCancelUrl()
        ));

        try {
            String nonce = UUID.randomUUID().toString();
            String bodyJson = objectMapper.writeValueAsString(body);
            String signature = generateSignature(channelSecret, endpoint, bodyJson, nonce);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-LINE-ChannelId", channelId);
            headers.set("X-LINE-Authorization-Nonce", nonce);
            headers.set("X-LINE-Authorization", signature);

            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonNode = objectMapper.readTree(response.getBody());
                return jsonNode.at("/info/paymentUrl/web").asText();
            } else {
                log.error("LinePay error: {}", response.getBody());
                throw new RuntimeException("LinePay request failed");
            }
        } catch (Exception e) {
            log.error("Exception calling LinePay", e);
            throw new RuntimeException("LinePay internal error");
        }
    }

    private String generateSignature(String secretKey, String uri, String body, String nonce) throws Exception {
        String message = secretKey + uri + body + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmac);
    }

    @Transactional
    public String confirmPayment(String transactionId, LinePayConfirmDTO confirmDTO) {
        String endpointPath = "/v3/payments/" + transactionId + "/confirm";
        String endpointUrl = apiUrl + endpointPath;

        // 產生 nonce
        String nonce = UUID.randomUUID().toString();

        // 🔒 準備 JSON body（記得順序、格式一樣才會 match）
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("amount", confirmDTO.getAmount());
        bodyMap.put("currency", confirmDTO.getCurrency());

        // 將 body 轉成 JSON 字串
        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(bodyMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert request body to JSON", e);
        }

        // 💡 計算簽名：secret + path + body + nonce
        String rawSignature = channelSecret + endpointPath + requestBody + nonce;
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(channelSecret.getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(rawSignature.getBytes());
            signature = Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate signature", e);
        }

        // 設定 HTTP headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-LINE-ChannelId", channelId);
        headers.set("X-LINE-Authorization", signature);          // ✅ 改對 header 名稱
        headers.set("X-LINE-Authorization-Nonce", nonce);        // ✅ 改對 header 名稱

        // 🔍 log for debug
        log.info("[LinePay] 正在送出確認請求: {}", requestBody);
        log.info("[LinePay] 簽章: {}", signature);

        // 發送請求
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(endpointUrl, entity, String.class);

        log.info("[LinePay] 回應狀態碼: {}", response.getStatusCode());
        log.info("[LinePay] 回應內容: {}", response.getBody());

        if (response.getStatusCode() == HttpStatus.OK) {
            log.info("[LinePay] LINE Pay 確認成功！");

            // ✅ 儲存資料到 DB
            Order order = orderRepository.findById(confirmDTO.getOrderId())
                    .orElseThrow(() -> new RuntimeException("Order not found"));

            // ✅ 扣庫存（依照訂單項目）
            for (OrderItem item : order.getOrderItems()) {
                Product p = item.getProduct();
                int newQty = p.getQuantity() - item.getQuantity();
                if (newQty < 0) {
                    throw new RuntimeException("Insufficient stock for productId=" + p.getProductId());
                }
                p.setQuantity(newQty);
                productRepository.save(p);
            }

            // ✅ 清購物車（用 email 對應的 cart）
            Cart cart = cartRepository.findCartByEmail(order.getEmail());
            if (cart != null && cart.getCartItems() != null) {
                // 如果你有 cartService 的刪除方法，改用它（會處理關聯與回寫）：
                // for (OrderItem item : order.getOrderItems()) {
                //     cartService.deleteProductFromCart(cart.getCartId(), item.getProduct().getProductId());
                // }

                // 沒有 cartService 的情況，直接清空這筆購物車（簡單暴力且安全）
                cart.getCartItems().clear();
                cartRepository.save(cart);
            }

            // 2️⃣ 建立並儲存 Payment 實體
            Payment payment = new Payment();
            payment.setPgName(confirmDTO.getPgName());
            payment.setPgPaymentId(confirmDTO.getPgPaymentId());
            payment.setPgStatus(confirmDTO.getPgStatus());
            payment.setPgResponseMessage(confirmDTO.getPgResponseMessage());
            payment.setPaymentMethod("LinePay");
            payment.setOrder(order); // 關聯這筆付款到訂單
            paymentRepository.save(payment);

            // 3️⃣ 更新 Order 狀態
            order.setOrderStatus("Order Accepted !");
            order.setPayment(payment); // 關聯付款
            orderRepository.save(order);

            return "Payment Confirmed!";
        } else {
            log.error("Confirm Failed: " + response.getBody());
            throw new RuntimeException("LinePay confirmation failed");
        }
    }


}

