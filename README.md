# 電商平台（Ecommerce System）

# 系統介紹
本專案為模擬 線上購物商城 的練習專案，採用 前後端分離架構：
- 前端：以 React.js + Tailwind CSS 開發，提供使用者操作介面。
- 後端：以 Spring Boot + MySQL 建立 RESTful API，並支援 JWT 驗證與授權。
- 支付串接：支援 Line Pay 與 Stripe，模擬完整線上購物付款流程。
- 本檔案為 **前端原始碼**，後端原始碼請見：(link)

# 功能介紹
- 使用者管理：註冊 / 登入 / JWT Token 驗證。
- 商品類型/商品 CRUD：建立、更新、刪除、查詢。
- 購物車：加入商品、調整數量、刪除商品。
- 地址 CRUD：建立、更新、刪除、查詢。
- 訂單結帳：使用LinePay或Stripe付款、送出訂單。
- 訂單管理：歷史訂單狀態查詢。

# 系統架構
### 系統架構圖
下圖為系統功能架構圖，將前述五大模組與其細部功能以總覽方式呈現：
<br/>

### 程式分層設計
後端採用 **分層架構設計**，依職責將程式劃分Controller、Service、Repository 與 Model，各層責任如下：
- **Controller 層**：接收Api請求，回傳 JSON 回應（例如：`ProductController`, `OrderController`）  
- **Service 層**：處理業務邏輯（例如：`ProductService`, `OrderService`）  
- **Repository 層**：透過 Spring Data JPA 與 MySQL 互動（例如：`ProductRepository`, `OrderRepository`）  
- **Model 層**：資料表對應的實體類別（例如：`User`, `Product`, `Order`）
<br/>

### 資料庫設計
本系統主要資料表及其關聯設計如下所示：
- `users`：使用者  
- `roles`：角色  
- `users_roles`：使用者與角色關聯  
- `todos`：任務  
- `todo_items`：子任務  
- `messages`：留言  

### 使用技術
- Java Spring Boot
- MySQL

## 本機安裝與使用

1. 建立資料庫
   ```bash
   CREATE DATABASE ecom_db;
   ```
2. 取得原始碼
   ```bash
   git clone https://github.com/felixven/ecommerce-backend.git
   cd commerce-backend
   ```
   
3. 設定資料庫與環境  
   編輯 `src/main/resources/application.properties`，填入以下內容：

   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/ecom_db
   spring.datasource.username=root
   spring.datasource.password=yourpassword

   spring.jpa.hibernate.ddl-auto=update
   spring.jpa.show-sql=true

   # JWT 金鑰（自行更換）
   jwt.secret=your-secret-key
   ```
   
4. 啟動後端服務
   ```bash
   ./mvnw spring-boot:run
   #預設服務位置：http://localhost:8080
   ```
5. 預設帳號
   專案啟動後會自動建立預設帳號，可直接登入測試，亦可透過註冊 API 建立新帳號：
   - Admin帳號
     - 帳號：
     - 密碼：
   - User帳號
     - 帳號：
     - 密碼： 
7. 測試 API (Postman Collection)
  - 匯入本專案提供的 [Postman Collection](docs/todo-api.postman_collection.json)
  - 匯入本專案提供的 [Postman Environment](docs/local_env.json)
  - 開啟 Postman，選擇 `local_env` 環境，點選 **Run Collection**
  
- 範例測試流程（Admin權限可執行所有Api）：  
     1. 會員註冊
        **Request** 
        `POST api/auth/signup`
        
     2. 會員登入
         **Request** 
        `POST api/auth/signin`
        
     3. 建立商品類型（Admin專有權限）  
        **Request**  
        `POST /api/public/categories`  

        **Body (JSON)**  
        ```json
        {
          "categoryName":"3C"
        }
        ```
        
     4. 建立新商品（Admin專有權限 ）
        **Request**  
        `POST /api/admin/categories/{categoryId}/product`  

        **Body (JSON)**  
        ```json
        {
          "productName": "iPhone 16",
          "description": "",
          "quantity": 26,
          "price": ,
          "discount": 
        }
        ```
        
     5. 加入商品到購物車 
        **Request**  
        `PUT /api/carts/products/{productId}/quantity/{quantity}`  
        
     6. 訂單送出（使用Stripe結帳）  
        **Request**  
        `POST api/order/users/payments/CARD`
        
     7. 訂單送出（使用LinePay結帳） 
        **Request**  
        `POST api/todos/{id}/participation`
   




