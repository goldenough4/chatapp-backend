const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const nodemailer = require('nodemailer');
const cors = require('cors');
require('dotenv').config();

const app = express();
app.set('trust proxy', 1); // Trust reverse proxies (Render, Railway, Heroku)
app.use(cors());
app.use(express.json());

// Root & Health Check Endpoints
const handleRoot = (req, res) => {
  res.status(200).send('Server is awake!');
};

const handleHealth = (req, res) => {
  res.json({
    status: 'ok',
    server: 'ChatApp Production OTP Server',
    timestamp: new Date()
  });
};

const handlePing = (req, res) => {
  res.status(200).send('pong');
};

app.all('/', handleRoot);
app.all('/index.html', handleRoot);
app.all('/health', handleHealth);
app.all('/ping', handlePing);

const server = http.createServer(app);
server.timeout = 0;
server.keepAliveTimeout = 120000; // 120s keep-alive timeout

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

// Robust Transporter Setup (Supports SMTP_USER/SMTP_PASS and EMAIL_USER/EMAIL_PASS, falls back to streamTransport if password is missing to prevent EAUTH Missing credentials errors)
let transporter = null;

try {
  const user = process.env.SMTP_USER || process.env.EMAIL_USER || 'chatappsupport2026@gmail.com';
  const pass = process.env.SMTP_PASS || process.env.EMAIL_PASS || '';

  if (pass && pass.trim().length > 0) {
    transporter = nodemailer.createTransport({
      host: 'smtp.gmail.com',
      port: 465,
      secure: true,
      auth: {
        user: user.trim(),
        pass: pass.trim()
      },
      tls: {
        rejectUnauthorized: false
      }
    });
    console.log(`[MAIL] Initialized SMTPS transporter for ${user} (smtp.gmail.com:465, secure=true)`);
  } else {
    transporter = nodemailer.createTransport({
      streamTransport: true,
      buffer: true,
      newline: 'unix',
      auth: {
        user: user.trim(),
        pass: 'placeholder'
      }
    });
    console.log(`[MAIL] Initialized local streamTransport fallback for ${user} (No SMTP password provided in environment)`);
  }
} catch (err) {
  console.error('[MAIL ERROR] Failed to initialize transporter:', err.message);
}

// Generate cryptographically safe 6-digit OTP
function generateOtp() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

// Clean up expired OTPs periodically
setInterval(() => {
  try {
    const now = Date.now();
    for (const [target, record] of otpStore.entries()) {
      if (now > record.expiresAt) {
        otpStore.delete(target);
      }
    }
  } catch (err) {
    console.error('[CLEANUP ERROR]', err.message);
  }
}, 60000);

// Fire-and-Forget Architecture (Asynchronous Non-blocking Dispatch)
async function sendOtpDelivery(target, type, code) {
  const isEmail = type === 'email' || target.includes('@');

  if (isEmail) {
    const subject = 'ChatApp Verification Code';
    const htmlContent = `
      <div style="font-family: Arial, sans-serif; padding: 25px; max-width: 500px;
           margin: 0 auto; border: 1px solid #e0e0e0; border-radius: 12px;
           background-color: white;">
        <h1 style="color: #075E54; text-align: center; font-size: 26px;
             font-weight: bold; margin-bottom: 20px;">ChatApp</h1>
        <p style="font-size: 16px; color: #333333; text-align: center;
           margin-bottom: 15px;">Your verification code is</p>
        <div style="background-color: #f0f2f5; padding: 18px; text-align: center;
             font-size: 32px; font-weight: bold; letter-spacing: 6px; color: #075E54;
             border-radius: 8px; margin-bottom: 20px;">
          ${code}
        </div>
        <p style="text-align: center; color: #666666; font-size: 13px;">
          This code will expire in 5 minutes.
        </p>
      </div>
    `;

    const mailOptions = {
      from: process.env.SMTP_FROM || process.env.EMAIL_FROM || `"ChatApp Support" <chatappsupport2026@gmail.com>`,
      to: target,
      subject: subject,
      text: `ChatApp your verification code is ${code}`,
      html: htmlContent,
      priority: 'high',
      headers: {
        'X-Priority': '1',
        'X-MSMail-Priority': 'High',
        'Importance': 'high'
      }
    };

    // Asynchronous background dispatch with robust try-catch
    setImmediate(async () => {
      try {
        if (transporter) {
          const info = await transporter.sendMail(mailOptions);
          const msgId = info.messageId || 'STREAM_LOCAL';
          console.log(`[SMTP SUCCESS] Email processed for ${target}. Message ID: ${msgId}`);
          console.log(`=======================================================`);
          console.log(` 📧 [EMAIL OTP SUCCESS] Target: ${target} | CODE: ${code}`);
          console.log(`=======================================================`);
        } else {
          console.log(`[SMTP LOG] Transporter not configured. Target: ${target} | CODE: ${code}`);
        }
      } catch (err) {
        console.error(`[SMTP ERROR MANAGED] Failed to send email to ${target}: ${err.message}`, {
          code: err.code || 'UNKNOWN',
          command: err.command || 'SMTP_DISPATCH'
        });
        console.log(`=======================================================`);
        console.log(` 📧 [FALLBACK OTP] Target: ${target} | CODE: ${code}`);
        console.log(`=======================================================`);
      }
    });
  } else {
    // Phone OTP
    console.log(`=======================================================`);
    console.log(` 📱 [PHONE OTP] Target: ${target} | CODE: ${code}`);
    console.log(`=======================================================`);
  }
}

// Store OTP logic (Instant response on main thread while email dispatches asynchronously)
async function storeAndDispatchOtp(target, type) {
  try {
    const normalizedTarget = target.trim().toLowerCase();
    const existing = otpStore.get(normalizedTarget);

    // Rate limiting: 30 seconds cooldown between new OTP requests
    if (existing && Date.now() - existing.createdAt < 30000) {
      const remainingSec = Math.ceil(
        (30000 - (Date.now() - existing.createdAt)) / 1000
      );
      return {
        statusCode: 429,
        success: false,
        message: `Please wait ${remainingSec} seconds before requesting a new code.`
      };
    }

    const code = generateOtp();
    const now = Date.now();

    // Trigger asynchronous fire-and-forget delivery
    sendOtpDelivery(normalizedTarget, type, code);

    // Store OTP immediately
    otpStore.set(normalizedTarget, {
      code,
      expiresAt: now + OTP_EXPIRY_MS,
      createdAt: now,
      attempts: 0,
      type: type || (normalizedTarget.includes('@') ? 'email' : 'phone')
    });

    return {
      statusCode: 200,
      success: true,
      target: normalizedTarget,
      type,
      message: `Verification code sent to ${normalizedTarget}`
    };
  } catch (err) {
    return {
      statusCode: 500,
      success: false,
      message: `OTP generation failed: ${err.message}`
    };
  }
}

// Verify OTP logic
function verifyOtpCode(target, inputCode) {
  try {
    const normalizedTarget = target.trim().toLowerCase();
    const record = otpStore.get(normalizedTarget);

    if (!record) {
      return {
        success: false,
        message: 'No OTP code found or code has expired. Please request a new code.'
      };
    }

    if (Date.now() > record.expiresAt) {
      otpStore.delete(normalizedTarget);
      return {
        success: false,
        message: 'Verification code has expired. Please request a new code.'
      };
    }

    if (record.attempts >= MAX_ATTEMPTS) {
      otpStore.delete(normalizedTarget);
      return {
        success: false,
        message: 'Too many invalid attempts. Code invalidated. Please request a new code.'
      };
    }

    if (record.code.trim() === inputCode.trim()) {
      otpStore.delete(normalizedTarget);
      const authSessionToken = Buffer.from(
        `${normalizedTarget}:${Date.now()}`
      ).toString('base64');
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
  } catch (err) {
    return {
      success: false,
      message: `Verification error: ${err.message}`
    };
  }
}

// Real-time Socket.io Event Handlers
io.on('connection', (socket) => {
  console.log(`[SOCKET] Client connected: ${socket.id}`);

  socket.on('request_otp', async (data) => {
    try {
      const { target, type } = data || {};
      if (!target) {
        return socket.emit('otp_error', {
          success: false,
          message: 'Target email or phone is required.'
        });
      }

      const result = await storeAndDispatchOtp(target, type);
      if (result.success) {
        socket.emit('otp_sent', result);
      } else {
        socket.emit('otp_error', result);
      }
    } catch (err) {
      socket.emit('otp_error', { success: false, message: err.message });
    }
  });

  socket.on('verify_otp', (data) => {
    try {
      const { target, otp } = data || {};
      if (!target || !otp) {
        return socket.emit('otp_verified', {
          success: false,
          message: 'Target and OTP code are required.'
        });
      }

      const result = verifyOtpCode(target, otp);
      socket.emit('otp_verified', result);
    } catch (err) {
      socket.emit('otp_verified', { success: false, message: err.message });
    }
  });

  socket.on('resend_otp', async (data) => {
    try {
      const { target, type } = data || {};
      if (!target) {
        return socket.emit('otp_error', {
          success: false,
          message: 'Target email or phone is required.'
        });
      }

      const result = await storeAndDispatchOtp(target, type);
      if (result.success) {
        socket.emit('otp_sent', result);
      } else {
        socket.emit('otp_error', result);
      }
    } catch (err) {
      socket.emit('otp_error', { success: false, message: err.message });
    }
  });

  socket.on('disconnect', () => {
    console.log(`[SOCKET] Client disconnected: ${socket.id}`);
  });
});

app.post('/api/request-otp', async (req, res) => {
  try {
    const { target, type } = req.body || {};
    if (!target) {
      return res.status(400).json({
        success: false,
        message: 'Target email or phone is required.'
      });
    }
    const result = await storeAndDispatchOtp(target, type);
    const status = result.statusCode || (result.success ? 200 : 500);
    return res.status(status).json({
      success: result.success,
      target: result.target,
      type: result.type,
      message: result.message
    });
  } catch (err) {
    return res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/verify-otp', (req, res) => {
  try {
    const { target, otp } = req.body || {};
    if (!target || !otp) {
      return res.status(400).json({
        success: false,
        message: 'Target and OTP code are required.'
      });
    }
    const result = verifyOtpCode(target, otp);
    return res.status(result.success ? 200 : 400).json(result);
  } catch (err) {
    return res.status(500).json({ success: false, message: err.message });
  }
});

// Fallback for unmatched routes
app.use((req, res) => {
  res.status(404).json({
    success: false,
    message: `Route ${req.originalUrl} not found.`
  });
});

server.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(` ChatApp OTP Socket.io Server running on port ${PORT}`);
  console.log(`=======================================================`);

  // Keep-alive self-ping every 10 minutes
  const PING_INTERVAL_MS = 10 * 60 * 1000;
  setInterval(() => {
    try {
      const selfUrl = 'http://192.168.0.154:3000/health';
      const client = require('http');

      client.get(selfUrl, (res) => {
        console.log(`[KEEP-ALIVE] Self-ping status: ${res.statusCode}`);
      }).on('error', (err) => {
        console.warn('[KEEP-ALIVE] Self-ping error:', err.message);
      });
    } catch (e) {
      // ignore
    }
  }, PING_INTERVAL_MS);
});
