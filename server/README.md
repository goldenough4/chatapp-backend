# ChatApp Backend Server (Node.js & Socket.io)

This is the production backend server for ChatApp, built with Express, Socket.io, and Nodemailer for 100% reliable OTP verification over email and phone.

---

## 🚀 Local Development

1. Install dependencies:
   ```bash
   npm install
   ```
2. Copy `.env.example` to `.env` and configure your credentials:
   ```bash
   cp .env.example .env
   ```
3. Start the server:
   ```bash
   npm start
   ```

---

## 📱 Connecting the Android Client

Once your backend server is running locally on Termux:
1. Open **[OtpSocketManager.kt](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/java/com/gold/chatapp/OtpSocketManager.kt)**.
2. Verify `PRODUCTION_SERVER_URL` points to your local Termux server IP:
   ```kotlin
   private const val PRODUCTION_SERVER_URL = "http://192.168.0.154:3000"
   ```
3. Rebuild and run the app. Your chat application will now communicate locally and reliably with your Termux server!

---

## ⚡ Real SMTP Email Delivery (Optional)

To enable sending verification emails through Gmail:
1. Go to your Google Account -> **Security** -> **2-Step Verification** -> **App Passwords**.
2. Generate a 16-character app password named "ChatApp".
3. Set the environment variables on Render (`SMTP_USER` and `SMTP_PASS`).
