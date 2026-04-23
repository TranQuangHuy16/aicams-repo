# AICAMS (AI-Augmented Capstone Monitoring, Assessment, and Early-Warning System)

**AICAMS** là hệ thống hỗ trợ giám sát, đánh giá và cảnh báo sớm tiến độ đồ án tốt nghiệp (Capstone Project) dành cho sinh viên. Hệ thống tự động hóa quy trình quản lý bằng cách tích hợp trực tiếp với các nền tảng quản lý mã nguồn và quản lý dự án (GitHub, Jira), kết hợp cùng sức mạnh của AI (Gemini) để đưa ra đánh giá thông minh.

## 🚀 Tính năng nổi bật
* **Quản lý & Giám sát tiến độ:** Tích hợp với Jira và GitHub thông qua Webhook để theo dõi Task và Pull Request theo thời gian thực.
* **Cảnh báo tự động:** Hệ thống tự động phân tích và gửi thông báo qua email (thông qua SendGrid) khi có PR mới hoặc PR sai định dạng chuẩn.
* **Xác thực an toàn (OAuth2):** Đăng nhập nhanh chóng và bảo mật với Google, GitHub và Jira (Atlassian).
* **AI Early-Warning:** Tích hợp Gemini AI để đánh giá và dự báo rủi ro chậm tiến độ của các nhóm Capstone.

## 🛠️ Công nghệ sử dụng
* **Backend:** Java, Spring Boot 3.x
* **Database:** PostgreSQL (Lưu trữ chính), Redis (Caching)
* **Message Broker:** RabbitMQ (Xử lý hàng đợi Webhook bất đồng bộ)
* **Third-party APIs:** SendGrid (Email), Cloudinary (Images), Gemini AI, Atlassian API.

## ⚙️ Hướng dẫn cài đặt & Chạy cục bộ (Local Development)

### 1. Yêu cầu hệ thống
* Java 17 hoặc mới hơn
* Maven 3.8+
* PostgreSQL, Redis, RabbitMQ đã được cài đặt và chạy (có thể dùng Docker).

### 2. Thiết lập biến môi trường
Tạo một file `.env` tại thư mục gốc của dự án và điền các thông tin bảo mật sau (đừng quên thêm file này vào `.gitignore`):

```env
# Database
DB_URL=jdbc:postgresql://localhost:5432/aicams_db
DB_USERNAME=postgres
DB_PASSWORD=your_password

# RabbitMQ & Redis
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
REDIS_HOST=localhost
REDIS_PORT=6379

# Email Configuration (SendGrid)
EMAIL_API_KEY=SG.xxxxxxxxxxxxxxxxx
EMAIL_SENDER_EMAIL=your-verified-email@gmail.com

# APIs (Gemini, Cloudinary, OAuth2...)
GEMINI_API_KEY=your_gemini_key
GOOGLE_CLIENT_ID=your_google_id
GOOGLE_CLIENT_SECRET=your_google_secret
GITHUB_WEBHOOK_SECRET=your_secret