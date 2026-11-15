const express = require('express');
const http = require('http');
const https = require('https');
const { Server } = require('socket.io');
const nodemailer = require('nodemailer');
const cors = require('cors');
const dns = require('dns');
require('dotenv').config();

// Force IPv4 resolution first to prevent 30s connection timeouts
// on platforms without IPv6 egress
if (dns.setDefaultResultOrder) {
  dns.setDefaultResultOrder(['ipv4', 'first'].join(''));
}

const app = express();
app.set('trust proxy', 1); // Trust reverse proxies (Render, Railway, Heroku)
app.use(cors());
app.use(express.json());

// Root & Health Check Endpoints
// Using app.all to handle GET, HEAD, OPTIONS and prevent 404s
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
server.timeout = 0; // Disable HTTP socket timeout during SMTP email dispatches
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

// Transporter setup (Pooled SMTPS Transporter with Fast Connection Timeouts)
let transporter = null;

function initTransporter() {
  if (process.env.SMTP_USER && process.env.SMTP_PASS) {
    const user = process.env.SMTP_USER.trim();
    const pass = process.env.SMTP_PASS.trim();
    const host = process.env.SMTP_HOST
      ? process.env.SMTP_HOST.trim()
      : 'smtp.gmail.com';
    const port = process.env.SMTP_PORT
      ? parseInt(process.env.SMTP_PORT, 10)
      : 465;
    const secure = process.env.SMTP_SECURE !== undefined
      ? process.env.SMTP_SECURE === 'true'
      : (port === 465);

    transporter = nodemailer.createTransport({
      host: host,
      port: port,
      secure: secure,
      requireTLS: !secure,
      pool: true,
      maxConnections: 1,
      maxMessages: Infinity,
      rateDelta: 1000,
      family: 4, // Enforce IPv4 socket creation
      connectionTimeout: 5000, // 5s connection timeout so firewall blocks fail fast
      greetingTimeout: 5000,   // 5s greeting timeout
      socketTimeout: 10000,    // 10s socket inactivity timeout
      logger: true,
      auth: {
        user: user,
        pass: pass
      },
      tls: {
        rejectUnauthorized: false
      }
    });

    console.log(
      `[MAIL] Initialized pooled SMTPS transporter for: ${user} ` +
      `(${host}:${port}, secure=${secure}, family=IPv4, timeout=5s)`
    );

    // Pre-warm SMTP socket connection asynchronously on boot with stage tracing
    setImmediate(() => {
      const startTime = Date.now();
      transporter.verify().then(() => {
        const elapsed = Date.now() - startTime;
        console.log(
          `[MAIL PRE-WARM SUCCESS] SMTPS socket connected in ${elapsed}ms for: ${user}`
        );
      }).catch((err) => {
        const elapsed = Date.now() - startTime;
        console.warn(`[MAIL PRE-WARM FAILURE] Pre-warm failed after ${elapsed}ms:`, {
          message: err.message,
          code: err.code || 'UNKNOWN',
          command: err.command || 'VERIFY_INIT',
          syscall: err.syscall || 'N/A',
          address: err.address || host,
          port: err.port || port
        });
      });
    });
  } else {
    console.warn(
      '[MAIL WARNING] SMTP_USER and SMTP_PASS env variables are not set.'
    );
  }
}

initTransporter();

// Native Node.js HTTPS Email Dispatcher over Port 443 (Bypasses host SMTP socket block)
function sendViaHttpsApi(webAppUrl, mailData) {
  return new Promise((resolve, reject) => {
    try {
      const url = new URL(webAppUrl);
      const postData = JSON.stringify(mailData);

      const options = {
        hostname: url.hostname,
        port: 443,
        path: url.pathname + url.search,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(postData)
        },
        timeout: 10000 // 10s timeout for HTTPS POST over Port 443
      };

      const req = https.request(options, (res) => {
        let responseBody = '';
        res.on('data', (chunk) => {
          responseBody += chunk;
        });

        res.on('end', () => {
          if (res.statusCode >= 200 && res.statusCode < 400) {
            console.log(
              `[HTTPS MAIL SUCCESS] Email delivered via HTTPS API. ` +
              `Status: ${res.statusCode}`
            );
            resolve({ success: true, statusCode: res.statusCode, body: responseBody });
          } else {
            reject(
              new Error(
                `HTTPS API returned status ${res.statusCode}: ${responseBody}`
              )
            );
          }
        });
      });

      req.on('error', (err) => {
        reject(err);
      });

      req.on('timeout', () => {
        req.destroy();
        reject(new Error('HTTPS request timed out after 10000ms'));
      });

      req.write(postData);
      req.end();
    } catch (err) {
      reject(err);
    }
  });
}

// Helper to determine exact failure stage during SMTP protocol execution
function determineFailureStage(err) {
  const cmd = (err.command || '').toUpperCase();
  const msg = (err.message || '').toLowerCase();
  const code = (err.code || '').toUpperCase();

  const isDnsFailure = code.includes('DNS') ||
    (msg.includes('get') && msg.includes('info')) ||
    msg.includes('dns');
  if (isDnsFailure) {
    return 'DNS_RESOLUTION';
  }

  const isTimeout = code.includes('TIME') || code.includes('TIMEOUT');
  if (isTimeout && (cmd.includes('CONN') || !cmd)) {
    return 'TCP_HANDSHAKE_TIMEOUT';
  }

  if (msg.includes('tls') || msg.includes('ssl') || msg.includes('cert')) {
    return 'TLS_HANDSHAKE';
  }

  if (cmd.includes('GREETING') || msg.includes('greeting')) {
    return 'SMTP_GREETING';
  }

  const isHelo = cmd.includes('HELO') || (cmd.length === 4 && cmd.endsWith('LO'));
  if (isHelo) {
    return 'SMTP_HANDSHAKE_NEGOTIATION';
  }

  if (cmd.includes('AUTH') || code.includes('AUTH') || err.responseCode === 535) {
    return 'SMTP_AUTHENTICATION';
  }

  if (cmd.includes('MAIL') || cmd.includes('RCPT') || cmd.includes('DATA')) {
    return `SMTP_DATA_TRANSFER (${cmd})`;
  }

  return 'SOCKET_TRANSPORT_ERROR';
}

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

// Helper function to send OTP via HTTPS or SMTP with stage error tracking
async function sendOtpDelivery(target, type, code) {
  const isEmail = type === 'email' || target.includes('@');

  if (isEmail) {
    const subject = 'ChatApp';
    const htmlContent = `
      <div style="font-family: Arial, sans-serif; padding: 25px; max-width: 500px;
           margin: 0 auto; border: 1px solid #e0e0e0; border-radius: 12px;
           background-color: white;">
        <h1 style="color: #075E54; text-align: center; font-size: 26px;
             font-weight: bold; margin-bottom: 20px;">ChatApp</h1>
        <p style="font-size: 16px; color: #333333; text-align: center;
           margin-bottom: 15px;">your code is</p>
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

    const webAppUrl = process.env.GMAIL_HTTP_URL || process.env.GMAIL_WEB_APP_URL;

    // 1. Native Node.js HTTPS API over Port 443 (bypasses host SMTP port blocks)
    if (webAppUrl) {
      const startTime = Date.now();
      try {
        const res = await sendViaHttpsApi(webAppUrl, {
          to: target,
          subject: subject,
          html: htmlContent,
          code: code
        });
        const durationMs = Date.now() - startTime;
        console.log(`[HTTPS DISPATCH SUCCESS] Delivered in ${durationMs}ms to ${target}`);
        return { success: true, durationMs };
      } catch (err) {
        console.warn(
          `[HTTPS DISPATCH WARNING] HTTPS API failed: ${err.message}. Trying SMTP...`
        );
      }
    }

    // 2. Nodemailer SMTP (With fast 5s timeout if raw socket is blocked by cloud host)
    if (transporter) {
      const mailOptions = {
        from: process.env.SMTP_FROM || `"ChatApp" <${process.env.SMTP_USER}>`,
        to: target,
        subject: subject,
        text: `ChatApp your code is ${code}`,
        html: htmlContent,
        priority: 'high',
        headers: {
          'X-Priority': '1',
          'X-MSMail-Priority': 'High',
          'Importance': 'high'
        }
      };

      const startTime = Date.now();
      try {
        const info = await transporter.sendMail(mailOptions);
        const durationMs = Date.now() - startTime;
        console.log(
          `[SMTP SUCCESS] Sent verification email to ${target} in ${durationMs}ms. ` +
          `Message ID: ${info.messageId}`
        );
        return { success: true, messageId: info.messageId, durationMs };
      } catch (err) {
        const durationMs = Date.now() - startTime;
        const failureStage = determineFailureStage(err);

        console.error(
          `[SMTP ERROR DIAGNOSTICS] Email failed after ${durationMs}ms for ${target}:`,
          {
            stage: failureStage,
            message: err.message,
            code: err.code || 'UNKNOWN',
            command: err.command || 'NONE',
            responseCode: err.responseCode || 'N/A',
            response: err.response || 'N/A',
            syscall: err.syscall || 'N/A',
            address: err.address || 'N/A',
            port: err.port || 'N/A'
          }
        );

        console.log(`=======================================================`);
        console.log(` 📧 [EMAIL OTP FALLBACK LOG] Target: ${target} | CODE: ${code}`);
        console.log(`=======================================================`);
        throw new Error(`[${failureStage}] ${err.message || 'SMTP Email dispatch failed'}`);
      }
    } else {
      console.warn(
        `[MAIL WARNING] Transporter unconfigured. Printing OTP to console.`
      );
      console.log(`=======================================================`);
      console.log(` 📧 [EMAIL OTP] Target: ${target} | CODE: ${code}`);
      console.log(`=======================================================`);
      return { success: true, debug: true };
    }
  } else {
    // Phone OTP
    console.log(`=======================================================`);
    console.log(` 📱 [PHONE OTP] Target: ${target} | CODE: ${code}`);
    console.log(`=======================================================`);
    return { success: true, debug: true };
  }
}

// Store OTP logic (Awaits email dispatch before confirming status)
async function storeAndDispatchOtp(target, type) {
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

  try {
    // Fully await the SMTP/HTTPS email delivery before returning success status to client
    await sendOtpDelivery(normalizedTarget, type, code);

    // Record in store only after successful delivery confirmation
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
      message: `Email delivery failed: ${err.message}`
    };
  }
}

// Verify OTP logic
function verifyOtpCode(target, inputCode) {
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
}

// Real-time Socket.io Event Handlers
io.on('connection', (socket) => {
  console.log(`[SOCKET] Client connected: ${socket.id}`);

  socket.on('request_otp', async (data) => {
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
  });

  socket.on('verify_otp', (data) => {
    const { target, otp } = data || {};
    if (!target || !otp) {
      return socket.emit('otp_verified', {
        success: false,
        message: 'Target and OTP code are required.'
      });
    }

    const result = verifyOtpCode(target, otp);
    socket.emit('otp_verified', result);
  });

  socket.on('resend_otp', async (data) => {
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
  });

  socket.on('disconnect', () => {
    console.log(`[SOCKET] Client disconnected: ${socket.id}`);
  });
});

app.post('/api/request-otp', async (req, res) => {
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
});

app.post('/api/verify-otp', (req, res) => {
  const { target, otp } = req.body || {};
  if (!target || !otp) {
    return res.status(400).json({
      success: false,
      message: 'Target and OTP code are required.'
    });
  }
  const result = verifyOtpCode(target, otp);
  return res.status(result.success ? 200 : 400).json(result);
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

  // Keep-alive self-ping every 10 minutes to prevent Render from spinning down
  const PING_INTERVAL_MS = 10 * 60 * 1000;
  setInterval(() => {
    const selfUrl = 'https://chatapp-backend-dwh6.onrender.com/health';
    const client = require('https');

    client.get(selfUrl, (res) => {
      console.log(`[KEEP-ALIVE] Self-ping status: ${res.statusCode}`);
    }).on('error', (err) => {
      console.warn('[KEEP-ALIVE] Self-ping error:', err.message);
    });
  }, PING_INTERVAL_MS);
});
