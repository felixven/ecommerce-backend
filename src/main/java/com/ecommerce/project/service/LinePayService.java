package com.ecommerce.project.service;

import com.ecommerce.project.payload.LinePayConfirmDTO;
import com.ecommerce.project.payload.LinePayRequestDTO;

public interface LinePayService {
    String reserve(LinePayRequestDTO linePayRequestDTO); // 傳回 redirect URI（導向付款頁）
    String confirmPayment(String transactionId, LinePayConfirmDTO confirmDTO);
}
