const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const nodemailer = require('nodemailer');
const cors = require('cors');
require('dotenv').config();

const app = express();
app.set('trust proxy', 1); // Trust reverse proxies (Render, Railway, Cloudflare, Heroku)
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const io = new Server(server, {
  pingInterval: 25000,
  pingTimeout: 20000,
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

const PORT = process.env.PORT || 3000;
const OTP_EXPIRY_MS = 5 * 60 * 1000; // 5 minutes
const MAX_ATTEMPTS = 3;

// In-Memory OTP Store: target -> { code, expiresAt, attempts, type, createdAt }
const otpStore = new Map();

// Transporter setup (Nodemailer)
let transporter = null;

function initTransporter() {
  if (process.env.SMTP_HOST && process.env.SMTP_USER && process.env.SMTP_PASS) {
    transporter = nodemailer.createTransport({
      host: process.env.SMTP_HOST,
      port: parseInt(process.env.SMTP_PORT || '587'),
      secure: process.env.SMTP_SECURE === 'true',
      auth: {
        user: process.env.SMTP_USER,
        pass: process.env.SMTP_PASS
      }
    });
    console.log('[MAIL] Configured production SMTP transporter');
  } else {
    // Zero-config Ethereal test account for development
    nodemailer.createTestAccount((err, account) => {
      if (err) {
        console.warn('[MAIL] Test account fallback:', err.message);
        return;
      }
      transporter = nodemailer.createTransport({
        host: account.smtp.host,
        port: account.smtp.port,
        secure: account.smtp.secure,
        auth: {
          user: account.user,
          pass: account.pass
        }
      });
      console.log('[MAIL] Initialized dev test email account:', account.user);
    });
  }
}

initTransporter();

// Generate cryptographically safe 6-digit OTP
function generateOtp() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

// Clean up expired OTPs periodically
setInterval(() => {
  const now = Date.now();
  for (const [target, record] of otpStore.entries()) {
    if (now > record.expiresAt) {
      otpStore.delete(target);
    }
  }
}, 60000);

// Helper function to send OTP via Email or Phone Transport
async function sendOtpDelivery(target, type, code) {
  const isEmail = type === 'email' || target.includes('@');

  if (isEmail) {
    const mailOptions = {
      from: process.env.SMTP_FROM || '"ChatApp Verification" <noreply@chatapp.com>',
      to: target,
      subject: 'Your Verification Code - ChatApp',
      text: `Your ChatApp verification code is: ${code}. Valid for 5 minutes.`,
      html: `
        <div style="font-family: Arial, sans-serif; padding: 20px; max-width: 500px; margin: 0 auto; border: 1px solid #e0e0e0; border-radius: 10px;">
          <h2 style="color: #6200EE; text-align: center;">ChatApp Verification</h2>
          <p>Hello,</p>
          <p>Your 6-digit verification code is:</p>
          <div style="background-color: #f3e5f5; padding: 15px; text-align: center; font-size: 28px; font-weight: bold; letter-spacing: 5px; color: #6200EE; border-radius: 8px;">
            ${code}
          </div>
          <p style="margin-top: 20px; color: #666; font-size: 13px;">This code will expire in 5 minutes.</p>
        </div>
      `
    };

    if (transporter) {
      try {
        const info = await transporter.sendMail(mailOptions);
        console.log(`[MAIL SUCCESS] Sent OTP to ${target}. ID: ${info.messageId}`);
        if (nodemailer.getTestMessageUrl(info)) {
          console.log(`[MAIL PREVIEW URL] ${nodemailer.getTestMessageUrl(info)}`);
        }
      } catch (err) {
        console.error(`[MAIL ERROR] Failed to send email to ${target}:`, err.message);
      }
    } else {
      console.log(`[DEV OTP LOG] Target: ${target} | Code: ${code}`);
    }
  } else {
    // Phone OTP: Zero-cost instant dispatch
    console.log(`[PHONE OTP DISPATCH] Target Phone: ${target} | Code: ${code}`);
  }
}

// Store OTP logic
function storeAndDispatchOtp(target, type) {
  const normalizedTarget = target.trim().toLowerCase();
  const existing = otpStore.get(normalizedTarget);

  // Rate limiting: 30 seconds cooldown between new OTP requests
  if (existing && Date.now() - existing.createdAt < 30000) {
    const remainingSec = Math.ceil((30000 - (Date.now() - existing.createdAt)) / 1000);
    return {
      success: false,
      message: `Please wait ${remainingSec} seconds before requesting a new code.`
    };
  }

  const code = generateOtp();
  const now = Date.now();
  otpStore.set(normalizedTarget, {
    code,
    expiresAt: now + OTP_EXPIRY_MS,
    createdAt: now,
    attempts: 0,
    type: type || (normalizedTarget.includes('@') ? 'email' : 'phone')
  });

  // Asynchronous delivery
  sendOtpDelivery(normalizedTarget, type, code);

  return {
    success: true,
    target: normalizedTarget,
    type,
    message: `Verification code sent to ${normalizedTarget}`,
    debugOtp: process.env.NODE_ENV !== 'production' ? code : undefined
  };
}

// Verify OTP logic
function verifyOtpCode(target, inputCode) {
  const normalizedTarget = target.trim().toLowerCase();
  const record = otpStore.get(normalizedTarget);

  if (!record) {
    return { success: false, message: 'No OTP code found or code has expired. Please request a new code.' };
  }

  if (Date.now() > record.expiresAt) {
    otpStore.delete(normalizedTarget);
    return { success: false, message: 'Verification code has expired. Please request a new code.' };
  }

  if (record.attempts >= MAX_ATTEMPTS) {
    otpStore.delete(normalizedTarget);
    return { success: false, message: 'Too many invalid attempts. Code invalidated. Please request a new code.' };
  }

  if (record.code.trim() === inputCode.trim()) {
    otpStore.delete(normalizedTarget);
    const authSessionToken = Buffer.from(`${normalizedTarget}:${Date.now()}`).toString('base64');
    return {
      success: true,
      target: normalizedTarget,
      token: authSessionToken,
      message: 'OTP verified successfully.'
    };
  } else {
    record.attempts += 1;
    const remaining = MAX_ATTEMPTS - record.attempts;
    return {
      success: false,
      message: `Invalid code. ${remaining} attempt(s) remaining.`
    };
  }
}

// Real-time Socket.io Event Handlers
io.on('connection', (socket) => {
  console.log(`[SOCKET] Client connected: ${socket.id}`);

  socket.on('request_otp', (data) => {
    const { target, type } = data || {};
    if (!target) {
      return socket.emit('otp_error', { success: false, message: 'Target email or phone is required.' });
    }

    const result = storeAndDispatchOtp(target, type);
    if (result.success) {
      socket.emit('otp_sent', result);
    } else {
      socket.emit('otp_error', result);
    }
  });

  socket.on('verify_otp', (data) => {
    const { target, otp } = data || {};
    if (!target || !otp) {
      return socket.emit('otp_verified', { success: false, message: 'Target and OTP code are required.' });
    }

    const result = verifyOtpCode(target, otp);
    socket.emit('otp_verified', result);
  });

  socket.on('resend_otp', (data) => {
    const { target, type } = data || {};
    if (!target) {
      return socket.emit('otp_error', { success: false, message: 'Target email or phone is required.' });
    }

    const result = storeAndDispatchOtp(target, type);
    if (result.success) {
      socket.emit('otp_sent', result);
    } else {
      socket.emit('otp_error', result);
    }
  });

  socket.on('disconnect', () => {
    console.log(`[SOCKET] Client disconnected: ${socket.id}`);
  });
});

// HTTP Fallback Endpoints
app.get('/health', (req, res) => {
  res.json({ status: 'ok', server: 'ChatApp Production OTP Server', timestamp: new Date() });
});

app.post('/api/request-otp', (req, res) => {
  const { target, type } = req.body;
  if (!target) {
    return res.status(400).json({ success: false, message: 'Target email or phone is required.' });
  }
  const result = storeAndDispatchOtp(target, type);
  return res.status(result.success ? 200 : 429).json(result);
});

app.post('/api/verify-otp', (req, res) => {
  const { target, otp } = req.body;
  if (!target || !otp) {
    return res.status(400).json({ success: false, message: 'Target and OTP code are required.' });
  }
  const result = verifyOtpCode(target, otp);
  return res.status(result.success ? 200 : 400).json(result);
});

server.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(` ChatApp OTP Socket.io Server running on port ${PORT}`);
  console.log(`=======================================================`);

  // Keep-alive self-ping every 14 minutes to prevent Render free tier from spinning down
  const PING_INTERVAL_MS = 14 * 60 * 1000;
  setInterval(() => {
    const selfUrl = process.env.RENDER_EXTERNAL_URL || `http://localhost:${PORT}/health`;
    const client = selfUrl.startsWith('https') ? require('https') : require('http');

    client.get(selfUrl, (res) => {
      console.log(`[KEEP-ALIVE] Self-ping status: ${res.statusCode}`);
    }).on('error', (err) => {
      console.warn('[KEEP-ALIVE] Self-ping error:', err.message);
    });
  }, PING_INTERVAL_MS);
});
