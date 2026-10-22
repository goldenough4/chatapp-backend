# ChatApp OTP Server - 100% Free Production Deployment Guide

This Node.js Socket.io server provides 100% free, unlimited Email & Phone OTP verification for ChatApp over HTTPS/WSS worldwide.

---

## 🚀 Free Cloud Deployment Steps (2 Minutes)

Because cloud hosting platforms (Render and Railway) require account ownership and security authentication, the fastest and most secure method is GitHub 1-click deployment:

### Option A: Deploy on Render.com
1. **Push to GitHub**: Push your project repository to GitHub.
2. **Create Web Service**: Go to [Render Dashboard](https://dashboard.render.com), click **New +** -> **Web Service**, and connect your GitHub repo.
3. **Configure Settings**:
   - **Root Directory**: `server`
   - **Build Command**: `npm install`
   - **Start Command**: `node index.js`
   - **Plan**: **Free**
4. **Copy Live URL**: Copy your live HTTPS URL (e.g. `https://your-app.onrender.com`).

### Option B: Deploy on Railway.app
1. **Push to GitHub**: Push your project repository to GitHub.
2. **Deploy from GitHub**: Go to [Railway Dashboard](https://railway.app), click **New Project** -> **Deploy from GitHub repo**, and select your repository. Railway automatically reads `server/railway.json`.
3. **Generate Domain**: In project settings, go to **Domains** and generate a public HTTPS URL.

---

## 📱 Connecting the Android Client

Once your backend is live on Render or Railway:
1. Open **[OtpSocketManager.kt](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/java/com/gold/chatapp/OtpSocketManager.kt)**.
2. Update `PRODUCTION_SERVER_URL` with your live HTTPS URL:
   ```kotlin
   private const val PRODUCTION_SERVER_URL = "https://chatapp-backend-dwh6.onrender.com"
   ```
3. Rebuild and run the app. Your chat application will now communicate securely over HTTPS/WSS worldwide on any Wi-Fi or cellular network!

---

## ⚡ Real SMTP Email Delivery (Optional)
In your Render or Railway dashboard -> **Environment** (or **Variables**), add:
- `SMTP_HOST`: `smtp.gmail.com`
- `SMTP_PORT`: `587`
- `SMTP_USER`: `your_email@gmail.com`
- `SMTP_PASS`: `your_app_password` (`Gmail App Password`)
- `SMTP_FROM`: `"ChatApp Verification" <noreply@chatapp.com>`
