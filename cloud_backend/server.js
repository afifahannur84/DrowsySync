const express = require('express');
const mongoose = require('mongoose');
const dotenv = require('dotenv');
const cors = require('cors');
// Brevo Web API is used for all email dispatch (HTTPS port 443 — no SMTP firewall issues on Render)
const PDFDocument = require('pdfkit');
// Import Models
const User = require('./models/User');
const FatigueLog = require('./models/FatigueLog');
const Verification = require('./models/Verification');
const VehicleOwnership = require('./models/VehicleOwnership');
const bcrypt = require('bcryptjs');

// Load environment variables
dotenv.config();

// Fix for Render IPv6 ENETUNREACH errors (Forces IPv4 first)
const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());

// ── Brevo API email helper ────────────────────────────────────────────────────
// Sends a single transactional email via the Brevo Web API (HTTPS/443).
// @param {string} toEmail   - Recipient email address
// @param {string} subject   - Email subject line
// @param {string} htmlBody  - HTML content of the email body
// @param {Array}  [attachments] - Optional Brevo attachment array objects
const sendBrevoEmail = async (toEmail, subject, htmlBody, attachments = []) => {
  const payload = {
    sender: { name: 'DrowsySync', email: process.env.BREVO_SENDER_EMAIL },
    to: [{ email: toEmail }],
    subject,
    htmlContent: htmlBody
  };
  if (attachments.length > 0) payload.attachment = attachments;

  const response = await fetch('https://api.brevo.com/v3/smtp/email', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'api-key': process.env.BREVO_API_KEY
    },
    body: JSON.stringify(payload)
  });

  if (!response.ok) {
    const errBody = await response.text();
    throw new Error(`Brevo API error ${response.status}: ${errBody}`);
  }
  return response.json();
};


// Connect to MongoDB Atlas
mongoose.connect(process.env.MONGO_URI)
  .then(() => console.log('✅ MongoDB connected successfully'))
  .catch((err) => console.error('❌ MongoDB connection error:', err));

// ==========================================
// HEALTH CHECK ENDPOINT
// ==========================================

// [GET /api/health]
// Lightweight route to keep Render free-tier server awake without querying MongoDB
app.get('/api/health', (req, res) => {
  res.status(200).json({
    status: "UP",
    message: "DrowsySync Engine is warm and awake!"
  });
});

// ==========================================
// AUTHENTICATION API ENDPOINTS
// ==========================================

// Helper function to generate a random 6-digit code
const generateVerificationCode = () => {
  return Math.floor(100000 + Math.random() * 900000).toString();
};

// [POST /api/auth/register]
app.post('/api/auth/register', async (req, res) => {
  try {
    const { name, email, password, vehicleId, phone, licenseSerial, emergencyName, emergencyPhone } = req.body;
    const vId = vehicleId || "UTEM_LOG_862B";

    const verificationCode = generateVerificationCode();
    const hashedPassword = await bcrypt.hash(password, 10);

    const tempUser = new Verification({
      name,
      email,
      password: hashedPassword,
      code: verificationCode,
      vehicleId: vId,
      phone: phone || "",
      licenseSerial: licenseSerial || "",
      emergencyName: emergencyName || "",
      emergencyPhone: emergencyPhone || ""
    });

    await tempUser.save();

    // ── Send OTP via Brevo Web API ─────────────────────────────────────────────
    if (process.env.BREVO_API_KEY) {
      try {
        await sendBrevoEmail(
          email,
          'Your DrowsySync Verification Code',
          `
            <div style="font-family: 'Times New Roman', Times, serif; padding: 20px; background-color: #ffffff; border: 1px solid #e0e0e0; border-radius: 4px; max-width: 600px; margin: 0 auto;">
              <h2 style="color: #000000; font-weight: normal; border-bottom: 1px solid #eeeeee; padding-bottom: 10px;">DrowsySync Account Verification</h2>
              <p style="color: #333333; line-height: 1.6;">Dear User,</p>
              <p style="color: #333333; line-height: 1.6;">Thank you for registering with the DrowsySync system. To complete your registration and verify your email address, please use the following authentication code:</p>
              <div style="background-color: #f5f5f5; padding: 15px; text-align: center; margin: 20px 0;">
                <h1 style="color: #000000; letter-spacing: 5px; margin: 0; font-family: monospace;">${verificationCode}</h1>
              </div>
              <p style="color: #333333; line-height: 1.6;">Please input this code into the mobile application to proceed. Do not share this code with anyone.</p>
              <p style="color: #333333; line-height: 1.6; margin-top: 30px;">Sincerely,<br>The DrowsySync Administration Team</p>
            </div>
          `
        );
        console.log(`✉️ [Brevo] Verification OTP dispatched successfully to ${email}`);
      } catch (mailError) {
        console.error('❌ [Brevo] Failed to dispatch verification OTP:', mailError);
        console.log(`⚠️ FALLBACK VERIFICATION CODE FOR ${email}: ${verificationCode}`);
        // Even if email fails, we return success so the user can be manually verified or use a fallback
      }
    } else {
      console.log(`⚠️ [Brevo] BREVO_API_KEY not set. Email skipped. Code is: ${verificationCode}`);
    }

    res.status(201).json({
      message: 'Registration staging complete. Please verify email.',
      verificationCode: verificationCode
    });
  } catch (error) {
    console.error('Registration error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [POST /api/auth/verify]
app.post('/api/auth/verify', async (req, res) => {
  try {
    const { email, code } = req.body;

    const tempUser = await Verification.findOne({ email, code });
    if (!tempUser) {
      return res.status(400).json({ error: 'Invalid or expired verification code' });
    }

    // Check if the plate is currently claimed by another user
    const existingOwnership = await VehicleOwnership.findOne({
      vehicleId: tempUser.vehicleId,
      isActive: true
    });
    if (existingOwnership) {
      return res.status(409).json({ error: 'This vehicle plate is currently registered to another owner. Ask them to release it first.' });
    }

    const newUser = new User({
      name: tempUser.name,
      email: tempUser.email,
      phone: tempUser.phone,
      licenseSerial: tempUser.licenseSerial,
      emergencyName: tempUser.emergencyName,
      emergencyPhone: tempUser.emergencyPhone,
      password: tempUser.password,
      isEmailVerified: true,
      vehicleId: tempUser.vehicleId,
      isGuestModeActive: false
    });

    await newUser.save();

    // Create VehicleOwnership record
    await new VehicleOwnership({
      userId: newUser._id,
      vehicleId: tempUser.vehicleId,
      isActive: true,
      activatedAt: new Date()
    }).save();
    console.log(`🔑 VehicleOwnership created: ${newUser.email} → ${tempUser.vehicleId}`);

    await Verification.deleteOne({ _id: tempUser._id });

    const safeUser = newUser.toObject();
    delete safeUser.password;

    res.status(200).json({ message: 'Email verified successfully', user: safeUser });
  } catch (error) {
    console.error('Verification error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [POST /api/auth/login]
app.post('/api/auth/login', async (req, res) => {
  try {
    const { email, password, vehicleId } = req.body;
    const vId = vehicleId || "UTEM_LOG_862B";

    // Find the user by email (one User document per email)
    const user = await User.findOne({ email }).select('+password');
    if (!user) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    const validPassword = await bcrypt.compare(password, user.password);
    if (!validPassword) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    // ── Enforce email verification ──────────────────────────────────────────
    if (!user.isEmailVerified) {
      return res.status(401).json({ error: 'Please verify your email before logging in.' });
    }

    // ── Vehicle plate ownership check ───────────────────────────────────────
    const activeOwnership = await VehicleOwnership.findOne({ vehicleId: vId, isActive: true });

    if (activeOwnership && activeOwnership.userId.toString() !== user._id.toString()) {
      // Plate is claimed by someone else
      return res.status(409).json({ error: 'This vehicle plate is currently registered to another owner. Ask them to release it first.' });
    }

    if (!activeOwnership) {
      // Plate is unclaimed — auto-claim for this user
      await new VehicleOwnership({
        userId: user._id,
        vehicleId: vId,
        isActive: true,
        activatedAt: new Date()
      }).save();
      console.log(`🔑 Auto-claimed plate ${vId} for user ${user.email}`);
    }

    // Update user's vehicleId cache and driving state
    user.vehicleId = vId;
    user.isCurrentlyDriving = true;
    await user.save();

    // Unclaim all other drivers of this vehicle
    await User.updateMany(
      { vehicleId: vId, _id: { $ne: user._id } },
      { $set: { isCurrentlyDriving: false } }
    );

    const safeUser = user.toObject();
    delete safeUser.password;

    res.status(200).json({ message: 'Login successful', user: safeUser });
  } catch (error) {
    console.error('Login error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [PUT /api/users/guest-mode/:userId]
app.put('/api/users/guest-mode/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const { isGuestModeActive } = req.body;

    const user = await User.findByIdAndUpdate(
      userId,
      { $set: { isGuestModeActive } },
      { new: true, runValidators: true }
    );

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    res.status(200).json({ message: 'Guest mode updated successfully', user });
  } catch (error) {
    console.error('Update guest mode error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [PUT /api/users/claim-vehicle/:userId]
app.put('/api/users/claim-vehicle/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const user = await User.findById(userId);

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Unclaim vehicle for all other users sharing this Car Plate Number
    await User.updateMany(
      { vehicleId: user.vehicleId },
      { $set: { isCurrentlyDriving: false, sessionActive: false } }
    );

    // Claim it for the current user — activate session, clear any pending reset
    user.isCurrentlyDriving = true;
    user.sessionActive = true;
    user.sessionResetPending = false;
    user.alarmDismissed = false;
    await user.save();

    // Adopt any orphaned logs (userId: null) for this vehicle
    await FatigueLog.updateMany(
      { vehicleId: user.vehicleId, userId: null },
      { $set: { userId: user._id } }
    );

    res.status(200).json({ message: 'Vehicle claimed successfully' });
  } catch (error) {
    console.error('Claim vehicle error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [PUT /api/users/unclaim-vehicle/:userId]
app.put('/api/users/unclaim-vehicle/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const user = await User.findById(userId);

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Stop session and signal Python to reset all counters
    user.isCurrentlyDriving = false;
    user.sessionActive = false;
    user.sessionResetPending = true;
    await user.save();

    res.status(200).json({ message: 'Vehicle unclaimed successfully' });
  } catch (error) {
    console.error('Unclaim vehicle error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [PUT /api/users/dismiss-alarm/:userId]
app.put('/api/users/dismiss-alarm/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const user = await User.findById(userId);

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Dismiss alarm AND signal Python to reset all counters to 0
    user.alarmDismissed = true;
    user.sessionResetPending = true;
    await user.save();

    res.status(200).json({ message: 'Alarm dismiss signal set successfully' });
  } catch (error) {
    console.error('Dismiss alarm error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [GET /api/session/:vehicleId] — Polled by Python every 1.5s for start/stop/reset signals
app.get('/api/session/:vehicleId', async (req, res) => {
  try {
    const { vehicleId } = req.params;

    // Find the active owner of this vehicle
    const user = await User.findOne({ vehicleId, isCurrentlyDriving: true })
      || await User.findOne({ vehicleId }).sort({ updatedAt: -1 });

    if (!user) {
      // No user configured for this vehicle — stay in standby
      return res.status(200).json({ sessionActive: false, resetCounters: false });
    }

    const resetCounters = user.sessionResetPending;

    // Consume the reset flag immediately so Python only resets once
    if (resetCounters) {
      user.sessionResetPending = false;
      await user.save();
    }

    res.status(200).json({
      sessionActive: user.sessionActive,
      resetCounters
    });
  } catch (error) {
    console.error('Session poll error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [POST /api/users/release-vehicle]
app.post('/api/users/release-vehicle', async (req, res) => {
  try {
    const { userId, password } = req.body;

    if (!userId || !password) {
      return res.status(400).json({ error: 'userId and password are required' });
    }

    const user = await User.findById(userId).select('+password');
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Verify password
    const validPassword = await bcrypt.compare(password, user.password);
    if (!validPassword) {
      return res.status(401).json({ error: 'Incorrect password' });
    }

    if (!user.vehicleId || user.vehicleId === '') {
      return res.status(400).json({ error: 'No vehicle is currently assigned to this account' });
    }

    const releasedPlate = user.vehicleId;

    // Deactivate the VehicleOwnership record
    await VehicleOwnership.updateMany(
      { userId: user._id, vehicleId: releasedPlate, isActive: true },
      { $set: { isActive: false, deactivatedAt: new Date() } }
    );

    // Clear user's vehicle assignment
    user.vehicleId = '';
    user.isCurrentlyDriving = false;
    await user.save();

    console.log(`🔓 User ${user.email} released vehicle plate: ${releasedPlate}`);
    res.status(200).json({ message: `Vehicle plate ${releasedPlate} released successfully. A new owner can now register with this plate.` });
  } catch (error) {
    console.error('Release vehicle error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// ==========================================
// FATIGUE LOG API ENDPOINTS
// ==========================================

// [GET /api/logs/report/:userId]
// [GET /api/logs/summary/:userId]
app.get('/api/logs/summary/:userId', async (req, res) => {
  try {
    const { userId } = req.params;

    const user = await User.findById(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Get client's current time and define the boundaries
    const now = new Date();
    // Start of "today" in local time (GMT+8 Malaysia Time)
    const myTime = new Date(now.getTime() + 8 * 60 * 60 * 1000);
    const startOfTodayMy = new Date(myTime);
    startOfTodayMy.setUTCHours(0, 0, 0, 0);
    // Shift back to UTC
    const startOfTodayUtc = new Date(startOfTodayMy.getTime() - 8 * 60 * 60 * 1000);

    // Last 7 days UTC
    const sevenDaysAgoUtc = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

    // Run queries in parallel
    const [todayLogs, weeklyLogs] = await Promise.all([
      FatigueLog.find({ userId, createdAt: { $gte: startOfTodayUtc } }),
      FatigueLog.find({ userId, createdAt: { $gte: sevenDaysAgoUtc } })
    ]);

    const getStats = (logs) => {
      let warning = 0;
      let critical = 0;
      logs.forEach(log => {
        if (log.stage === 3) {
          critical++;
        } else if (log.stage === 1 || log.stage === 2) {
          warning++;
        }
      });
      return { warning, critical, total: warning + critical };
    };

    res.status(200).json({
      today: getStats(todayLogs),
      weekly: getStats(weeklyLogs)
    });
  } catch (error) {
    console.error('Summary generation error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [GET /api/logs/report/:userId]
app.get('/api/logs/report/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const { year, month } = req.query;

    const user = await User.findById(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    const now = new Date();
    const yearInt = parseInt(year) || now.getFullYear();
    const monthInt = parseInt(month) || (now.getMonth() + 1);

    // Define the UTC start and end bounds for the selected month in GMT+8 terms
    const startDate = new Date(Date.UTC(yearInt, monthInt - 1, 1, 0, 0, 0));
    startDate.setTime(startDate.getTime() - 8 * 60 * 60 * 1000); // Shift start to UTC

    const endDate = new Date(Date.UTC(yearInt, monthInt, 1, 0, 0, 0));
    endDate.setTime(endDate.getTime() - 1); // Last millisecond of month
    endDate.setTime(endDate.getTime() - 8 * 60 * 60 * 1000); // Shift end to UTC

    const events = await FatigueLog.find({
      userId,
      createdAt: { $gte: startDate, $lte: endDate }
    }).sort({ createdAt: 1 }); // Sort chronologically for calculation/charting

    // Pre-calculate weekly aggregates for the vector bar chart
    // Weeks: 1-7, 8-14, 15-21, 22-31
    const weeklyData = [
      { warning: 0, critical: 0 },
      { warning: 0, critical: 0 },
      { warning: 0, critical: 0 },
      { warning: 0, critical: 0 }
    ];

    let totalWarnings = 0;
    let totalCritical = 0;

    events.forEach(event => {
      // Find local day (GMT+8) of the log
      const eventLocalTime = new Date(new Date(event.createdAt).getTime() + 8 * 60 * 60 * 1000);
      const day = eventLocalTime.getUTCDate();
      const weekIndex = Math.min(3, Math.floor((day - 1) / 7));

      if (event.stage === 3) {
        weeklyData[weekIndex].critical++;
        totalCritical++;
      } else if (event.stage === 1 || event.stage === 2) {
        weeklyData[weekIndex].warning++;
        totalWarnings++;
      }
    });

    const doc = new PDFDocument({ size: 'LETTER', margin: 50 });
    const buffers = [];
    doc.on('data', buffers.push.bind(buffers));

    doc.on('end', async () => {
      const pdfData = Buffer.concat(buffers);

      // ── Send PDF report via Brevo Web API ──────────────────────────────────
      if (process.env.BREVO_API_KEY) {
        try {
          const pdfBase64 = pdfData.toString('base64');
          const months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
          const monthName = months[monthInt - 1] || "Selected Month";

          await sendBrevoEmail(
            user.email,
            `Your DrowsySync Fatigue Report - ${monthName} ${yearInt}`,
            `
              <div style="font-family: Arial, sans-serif; padding: 20px; max-width: 600px; margin: 0 auto; background-color: #ffffff; border: 1px solid #e0e0e0; border-radius: 8px;">
                <h2 style="color: #2E5BFF; margin-bottom: 20px;">DrowsySync Driver Safety Summary</h2>
                <p style="color: #333333; line-height: 1.6; font-size: 14px;">Dear ${user.name},</p>
                <p style="color: #333333; line-height: 1.6; font-size: 14px;">Please find attached your visual driver fatigue report for the month of <strong>${monthName} ${yearInt}</strong>.</p>
                <p style="color: #333333; line-height: 1.6; font-size: 14px;">This PDF report contains visual weekly charts, key fatigue indicators, and log details. Review these statistics to analyze driving patterns and secure safer travel habits.</p>
                <div style="background-color: #f7f9fd; border-left: 4px solid #2E5BFF; padding: 12px; margin: 20px 0; border-radius: 4px;">
                  <strong style="color: #1A1B1E; font-size: 13px;">Month Overview:</strong>
                  <ul style="color: #555555; font-size: 13px; margin: 8px 0 0 0; padding-left: 20px;">
                    <li>Total Driving Events Logged: <strong>${events.length}</strong></li>
                    <li>Warning Alert Detections (Stage 1/2): <strong>${totalWarnings}</strong></li>
                    <li>Critical Alarms (Stage 3): <strong>${totalCritical}</strong></li>
                  </ul>
                </div>
                <p style="color: #888888; font-size: 12px; margin-top: 30px;">Sincerely,<br>The DrowsySync Analytics Team</p>
              </div>
            `,
            [
              {
                name: `DrowsySync_Report_${monthName}_${yearInt}.pdf`,
                content: pdfBase64
              }
            ]
          );
          console.log(`✉️ [Brevo] Fatigue report PDF dispatched successfully to ${user.email}`);
          res.status(200).json({ message: 'Report emailed successfully via Brevo' });
        } catch (mailError) {
          console.error('❌ [Brevo] Failed to dispatch fatigue report PDF:', mailError);
          res.status(500).json({ error: 'Failed to send report email via Brevo API' });
        }
      } else {
        res.status(200).json({ message: 'BREVO_API_KEY not set. PDF generated but not sent.' });
      }
    });

    // —— 1. Header block
    doc.rect(0, 0, 612, 100).fillColor('#2E5BFF').fill();
    doc.fillColor('#FFFFFF').fontSize(24).font('Helvetica-Bold').text('DrowsySync Analytics', 50, 30);
    doc.fontSize(12).font('Helvetica').text('Driver Fatigue Summary Report', 50, 62);

    // —— 2. Metadata details
    const months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
    const monthName = months[monthInt - 1] || "Selected Month";

    doc.fillColor('#1A1B1E').fontSize(10).font('Helvetica-Bold').text('DRIVER PROFILE', 50, 120);
    doc.font('Helvetica').text(`Driver Name: ${user.name}`, 50, 135);
    doc.text(`Vehicle Plate: ${user.vehicleId || 'N/A'}`, 50, 150);

    doc.font('Helvetica-Bold').text('REPORT DETAILS', 350, 120);
    doc.font('Helvetica').text(`Report Period: ${monthName} ${yearInt}`, 350, 135);
    doc.text(`Generated On: ${new Date().toLocaleDateString('en-MY', { timeZone: 'Asia/Kuala_Lumpur' })}`, 350, 150);

    // Separator line
    doc.moveTo(50, 170).lineTo(562, 170).strokeColor('#E9EBEF').strokeWidth(1).stroke();

    // —— 3. KPI Blocks
    // Left Box (Total logs)
    doc.rect(50, 185, 150, 65).fillColor('#F4F6FA').fill();
    doc.fillColor('#1A1B1E').fontSize(20).font('Helvetica-Bold').text(`${events.length}`, 65, 198);
    doc.fillColor('#717182').fontSize(8.5).font('Helvetica').text('Logs Recorded', 65, 226);

    // Middle Box (Warnings)
    doc.rect(220, 185, 150, 65).fillColor('#FFF4E5').fill();
    doc.fillColor('#FFA500').fontSize(20).font('Helvetica-Bold').text(`${totalWarnings}`, 235, 198);
    doc.fillColor('#717182').fontSize(8.5).font('Helvetica').text('Warning Alerts (S1/S2)', 235, 226);

    // Right Box (Critical)
    doc.rect(390, 185, 172, 65).fillColor('#FFEAEA').fill();
    doc.fillColor('#FF0000').fontSize(20).font('Helvetica-Bold').text(`${totalCritical}`, 405, 198);
    doc.fillColor('#717182').fontSize(8.5).font('Helvetica').text('Critical Alarms (S3)', 405, 226);

    // —— 4. Section Title: Weekly Distribution
    doc.fillColor('#2E5BFF').fontSize(14).font('Helvetica-Bold').text('Weekly Alert Distribution', 50, 275);

    // Vector Chart Area
    const axisX = 90;
    const axisY = 410;
    const chartHeight = 110;
    const chartWidth = 280;

    // Y Axis
    doc.moveTo(axisX, axisY).lineTo(axisX, axisY - chartHeight).strokeColor('#D2D2D6').strokeWidth(1.5).stroke();
    // X Axis
    doc.moveTo(axisX, axisY).lineTo(axisX + chartWidth, axisY).stroke();

    // Find max value in weekly data to scale chart
    let maxVal = 1;
    weeklyData.forEach(w => {
      if (w.warning > maxVal) maxVal = w.warning;
      if (w.critical > maxVal) maxVal = w.critical;
    });

    // Horizontal grid lines
    const gridLines = 4;
    for (let i = 1; i <= gridLines; i++) {
      const gVal = Math.round((maxVal / gridLines) * i * 10) / 10;
      const gY = axisY - (chartHeight / gridLines) * i;
      // Grid line
      doc.moveTo(axisX, gY).lineTo(axisX + chartWidth, gY).strokeColor('#E9EBEF').strokeWidth(1).stroke();
      // Y-axis label
      doc.fillColor('#717182').fontSize(8).font('Helvetica').text(`${gVal}`, axisX - 25, gY - 4, { width: 20, align: 'right' });
    }
    // Label "0"
    doc.fillColor('#717182').fontSize(8).font('Helvetica').text('0', axisX - 25, axisY - 4, { width: 20, align: 'right' });

    // Draw weekly bars
    for (let w = 0; w < 4; w++) {
      const warnCount = weeklyData[w].warning;
      const critCount = weeklyData[w].critical;

      const scaleWarn = (warnCount / maxVal) * chartHeight;
      const scaleCrit = (critCount / maxVal) * chartHeight;

      const groupStartX = axisX + 30 + w * 60;

      // Warning bar (Orange)
      if (warnCount > 0) {
        doc.rect(groupStartX, axisY - scaleWarn, 18, scaleWarn).fillColor('#FFA500').fill();
        // Count above bar
        doc.fillColor('#1A1B1E').fontSize(7).font('Helvetica-Bold').text(`${warnCount}`, groupStartX, axisY - scaleWarn - 8, { width: 18, align: 'center' });
      }

      // Critical bar (Red)
      if (critCount > 0) {
        doc.rect(groupStartX + 20, axisY - scaleCrit, 18, scaleCrit).fillColor('#FF0000').fill();
        // Count above bar
        doc.fillColor('#1A1B1E').fontSize(7).font('Helvetica-Bold').text(`${critCount}`, groupStartX + 20, axisY - scaleCrit - 8, { width: 18, align: 'center' });
      }

      // Week label
      doc.fillColor('#1A1B1E').fontSize(8).font('Helvetica').text(`Week ${w+1}`, groupStartX - 2, axisY + 6, { width: 44, align: 'center' });
    }

    // Chart Legend
    const legendX = 405;
    const legendY = 320;
    // Warning box
    doc.rect(legendX, legendY, 10, 10).fillColor('#FFA500').fill();
    doc.fillColor('#1A1B1E').fontSize(8.5).font('Helvetica').text('Warning Alert (Stage 1/2)', legendX + 15, legendY + 1);
    // Critical box
    doc.rect(legendX, legendY + 18, 10, 10).fillColor('#FF0000').fill();
    doc.text('Critical Alarm (Stage 3)', legendX + 15, legendY + 19);

    // Week Info box below legend
    doc.rect(legendX, legendY + 40, 155, 45).fillColor('#F4F6FA').fill();
    doc.fillColor('#717182').fontSize(7.5).font('Helvetica')
      .text('W1: Days 1–7\nW2: Days 8–14\nW3: Days 15–21\nW4: Days 22–End', legendX + 10, legendY + 45);

    // Separator line
    doc.moveTo(50, 445).lineTo(562, 445).strokeColor('#E9EBEF').strokeWidth(1).stroke();

    // —— 5. Alert log details (Table)
    doc.fillColor('#2E5BFF').fontSize(14).font('Helvetica-Bold').text('Detailed Alarm Log', 50, 460);

    // Table Header
    const tableTop = 485;
    doc.rect(50, tableTop, 512, 18).fillColor('#2E5BFF').fill();
    doc.fillColor('#FFFFFF').fontSize(8.5).font('Helvetica-Bold');
    doc.text('No.', 55, tableTop + 5);
    doc.text('Date & Time (GMT+8)', 80, tableTop + 5);
    doc.text('Status/Level', 260, tableTop + 5);
    doc.text('PERCLOS', 390, tableTop + 5);
    doc.text('Avg EAR', 450, tableTop + 5);
    doc.text('Yawn Count', 510, tableTop + 5);

    let currentY = tableTop + 18;
    const maxLogsToDraw = events.slice(0, 12); // Fit up to 12 logs on page 1

    maxLogsToDraw.forEach((event, index) => {
      const isEven = index % 2 === 0;
      if (isEven) {
        doc.rect(50, currentY, 512, 16).fillColor('#F9FAFC').fill();
      }

      doc.fillColor('#1A1B1E').fontSize(8).font('Helvetica');
      doc.text(`${index + 1}`, 55, currentY + 4);
      
      const eventTimeStr = new Date(new Date(event.createdAt).getTime() + 8 * 60 * 60 * 1000).toISOString()
        .replace('T', ' ').substring(0, 19);
      doc.text(eventTimeStr, 80, currentY + 4);

      // Highlight status by stage
      let statusColor = '#1A1B1E';
      if (event.stage === 3) statusColor = '#FF0000';
      else if (event.stage === 2) statusColor = '#FFA500';

      doc.fillColor(statusColor).font('Helvetica-Bold').text(`${event.status}`, 260, currentY + 4);
      doc.fillColor('#1A1B1E').font('Helvetica');

      doc.text(`${event.perclos.toFixed(1)}%`, 390, currentY + 4);
      doc.text(`${event.ear.toFixed(3)}`, 450, currentY + 4);
      doc.text(`${event.recent_yawn_count}`, 510, currentY + 4);

      currentY += 16;
    });

    if (events.length > 12) {
      doc.fillColor('#717182').fontSize(8.5).font('Helvetica-Oblique').text(`* Note: Showing the first 12 logs. There are ${events.length - 12} other logs recorded in this period.`, 50, currentY + 8);
    }

    doc.end();

  } catch (error) {
    console.error('Report generation error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [GET /api/logs/:userId]
app.get('/api/logs/:userId', async (req, res) => {
  try {
    const { userId } = req.params;

    // Fetch latest 50 events for this specific user, sorted by createdAt descending
    const events = await FatigueLog.find({ userId }).sort({ createdAt: -1 }).limit(50);
    res.status(200).json(events);
  } catch (error) {
    console.error('Error fetching user events:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// [GET /api/logs/latest/vehicle/:vehicleId]
app.get('/api/logs/latest/vehicle/:vehicleId', async (req, res) => {
  try {
    const { vehicleId } = req.params;
    // Sort by `timestamp` (ms epoch set by Python script) — ground truth for recency.
    // createdAt can lag by seconds on Render's free tier due to cold-start overhead.
    const log = await FatigueLog.findOne({ vehicleId }).sort({ timestamp: -1 });

    if (!log) {
      return res.status(404).json({ error: 'No logs found for this vehicle' });
    }

    res.status(200).json(log);
  } catch (error) {
    console.error('Error fetching latest log:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// SAFE INGESTION ROUTE [POST /api/logs]
const handleLogIngestion = async (req, res) => {
  try {
    const fatigueData = req.body;
    const { vehicleId } = fatigueData;

    if (!vehicleId) {
      return res.status(400).json({ error: 'vehicleId is required' });
    }

    // Look up the active owner via VehicleOwnership table first
    let matchedUser = null;
    const activeOwnership = await VehicleOwnership.findOne({ vehicleId, isActive: true });
    if (activeOwnership) {
      matchedUser = await User.findById(activeOwnership.userId);
    }

    // Fallback: try to find the user who actively claimed the Car Plate Number (backward compat)
    if (!matchedUser) {
      matchedUser = await User.findOne({ vehicleId, isCurrentlyDriving: true });
    }

    // Final fallback: first registered owner
    if (!matchedUser) {
      matchedUser = await User.findOne({ vehicleId });
    }

    if (!matchedUser) {
      return res.status(404).json({ error: 'No user found for this Car Plate Number.' });
    }

    if (matchedUser.isGuestModeActive) {
      fatigueData.userId = null;
    } else {
      fatigueData.userId = matchedUser._id;
    }

    const logEntry = new FatigueLog(fatigueData);
    await logEntry.save();

    // Check if the user dismissed the alarm
    let dismissAlarm = false;
    if (matchedUser && matchedUser.alarmDismissed) {
      dismissAlarm = true;
      matchedUser.alarmDismissed = false;
      await matchedUser.save();
      console.log('🔔 Remote alarm dismissal triggered for user:', matchedUser.email);
    }

    console.log('📥 Received and logged fatigue event for Car Plate Number', vehicleId, ':', fatigueData.status);
    res.status(201).json({ message: 'Event successfully logged', data: logEntry, dismissAlarm });
  } catch (error) {
    console.error('Error logging event:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
};

// Route for the new /api/logs endpoint
app.post('/api/logs', handleLogIngestion);

// [PUT /api/users/profile/:userId]
app.put('/api/users/profile/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const { email, phone } = req.body;
    
    const user = await User.findById(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    if (email) user.email = email;
    if (phone !== undefined) user.phone = phone; // allow empty phone
    
    await user.save();
    res.status(200).json({ message: 'Profile updated successfully', user });
  } catch (error) {
    if (error.code === 11000) {
      return res.status(400).json({ error: 'Email already exists' });
    }
    console.error('Update profile error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// Start the server
app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server is running on http://0.0.0.0:${PORT}`);
});
