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
      { $set: { isCurrentlyDriving: false } }
    );

    // Claim it for the current user
    user.isCurrentlyDriving = true;
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

    user.isCurrentlyDriving = false;
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

    user.alarmDismissed = true;
    await user.save();

    res.status(200).json({ message: 'Alarm dismiss signal set successfully' });
  } catch (error) {
    console.error('Dismiss alarm error:', error);
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
app.get('/api/logs/report/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const { days } = req.query;

    const user = await User.findById(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    let query = { userId };
    if (days) {
      const daysInt = parseInt(days);
      if (!isNaN(daysInt)) {
        const cutoffDate = new Date();
        cutoffDate.setDate(cutoffDate.getDate() - daysInt);
        query.createdAt = { $gte: cutoffDate };
      }
    }

    const events = await FatigueLog.find(query).sort({ createdAt: -1 });

    if (events.length === 0) {
      return res.status(404).json({ error: 'No fatigue logs found to generate a report.' });
    }

    const doc = new PDFDocument();
    const buffers = [];
    doc.on('data', buffers.push.bind(buffers));

    doc.on('end', async () => {
      const pdfData = Buffer.concat(buffers);

      // ── Send PDF report via Brevo Web API ──────────────────────────────────
      if (process.env.BREVO_API_KEY) {
        try {
          const pdfBase64 = pdfData.toString('base64');

          await sendBrevoEmail(
            user.email,
            'Your DrowsySync Fatigue Report',
            `
              <div style="font-family: Arial, sans-serif; padding: 20px; max-width: 600px; margin: 0 auto; background-color: #ffffff; border: 1px solid #e0e0e0; border-radius: 4px;">
                <h2 style="color: #2E5BFF;">DrowsySync — Driver Fatigue Report</h2>
                <p style="color: #333333; line-height: 1.6;">Dear ${user.name},</p>
                <p style="color: #333333; line-height: 1.6;">Please find your requested driver fatigue summary report attached to this email as a PDF document.</p>
                <p style="color: #333333; line-height: 1.6;">Review the enclosed data to monitor your alertness trends and promote safer driving habits.</p>
                <p style="color: #333333; line-height: 1.6; margin-top: 30px;">Sincerely,<br>The DrowsySync Analytics Team</p>
              </div>
            `,
            [
              {
                name: 'DrowsySync_Fatigue_Report.pdf',
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

    doc.fontSize(24).fillColor('#2E5BFF').text('DrowsySync Fatigue Report', { align: 'center' });
    doc.moveDown();
    doc.fontSize(14).fillColor('#333333').text(`Driver: ${user.name}`);
    doc.text(`Vehicle: ${user.carModel || 'N/A'} - ${user.carPlate || 'N/A'}`);
    doc.text(`Total Events: ${events.length}`);
    if (days) doc.text(`Timeframe: Last ${days} days`);
    doc.moveDown();

    events.slice(0, 50).forEach((event, index) => {
      const date = new Date(event.createdAt).toLocaleString();
      doc.fontSize(12).text(`${index + 1}. ${date} - ${event.status} (PERCLOS: ${event.perclos.toFixed(1)}%)`);
    });

    if (events.length > 50) {
      doc.moveDown().text(`...and ${events.length - 50} more events.`);
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
