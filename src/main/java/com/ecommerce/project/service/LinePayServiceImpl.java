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

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${linepay.channel.id}")
    private String channelId;
    @Value("${linepay.channel.secret}")
    private String channelSecret;
    @Value("${linepay.api.url}")
    private String apiUrl;
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

        //準備 JSON body（記得順序、格式一樣才會 match）
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

        //計算簽名：secret + path + body + nonce
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
        headers.set("X-LINE-Authorization", signature);          //改對 header 名稱
        headers.set("X-LINE-Authorization-Nonce", nonce);        //改對 header 名稱

        // log for debug
        log.info("[LinePay] 正在送出確認請求: {}", requestBody);
        log.info("[LinePay] 簽章: {}", signature);

        // 發送請求
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(endpointUrl, entity, String.class);

        log.info("[LinePay] 回應狀態碼: {}", response.getStatusCode());
        log.info("[LinePay] 回應內容: {}", response.getBody());

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Confirm Failed: {}", response.getBody());
            throw new RuntimeException("LinePay confirmation failed (HTTP)");
        }

        //只解析 returnCode，不動 DB
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String returnCode = root.path("returnCode").asText();
            String returnMessage = root.path("returnMessage").asText();

            if ("0000".equals(returnCode)) {
                log.info("[LinePay] 確認成功（returnCode=0000）");
                // 不在這裡做任何資料庫寫入
                // 讓前端／或你的後端下一步呼叫統一入口完成落袋
                return "CONFIRMED";
            } else {
                log.error("[LinePay] 確認失敗：{} {}", returnCode, returnMessage);
                throw new RuntimeException("LinePay confirmation failed: " + returnCode + " " + returnMessage);
            }
        } catch (Exception parseEx) {
            // 保守處理：若官方回應格式變動導致解析失敗，直接視為失敗，比較安全
            log.error("[LinePay] 回應解析失敗", parseEx);
            throw new RuntimeException("LinePay confirmation parse error", parseEx);
        }
    }


}

