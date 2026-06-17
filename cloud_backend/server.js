const express = require('express');
const mongoose = require('mongoose');
const dotenv = require('dotenv');
const cors = require('cors');
const nodemailer = require('nodemailer');
const PDFDocument = require('pdfkit');
// Import Models
const User = require('./models/User');
const FatigueLog = require('./models/FatigueLog');
const Verification = require('./models/Verification');
const bcrypt = require('bcryptjs');

// Load environment variables
dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());

// Nodemailer Transport Setup
const transporter = nodemailer.createTransport({
  service: 'gmail',
  auth: {
    user: process.env.EMAIL_USER,
    pass: process.env.EMAIL_PASS
  }
});


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
    const { name, email, password, vehicleId } = req.body;
    const vId = vehicleId || "UTEM_LOG_862B";
    
    const verificationCode = generateVerificationCode();
    const hashedPassword = await bcrypt.hash(password, 10);
    
    const tempUser = new Verification({
      name,
      email,
      password: hashedPassword,
      code: verificationCode,
      vehicleId: vId
    });

    await tempUser.save();
    
    // Send Real Email
    if (process.env.EMAIL_USER && process.env.EMAIL_USER !== 'your_gmail@gmail.com') {
      try {
        await transporter.sendMail({
          from: `"DrowsySync" <${process.env.EMAIL_USER}>`,
          to: email,
          subject: 'Your DrowsySync Verification Code',
          html: `
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
        });
        console.log(`✉️ Verification email sent to ${email}`);
      } catch (mailError) {
        console.error('❌ Failed to send email:', mailError);
      }
    } else {
      console.log(`⚠️ Email skipped: Please fill EMAIL_USER and EMAIL_PASS in .env. Code is: ${verificationCode}`);
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

    const newUser = new User({
      name: tempUser.name,
      email: tempUser.email,
      password: tempUser.password,
      isEmailVerified: true,
      vehicleId: tempUser.vehicleId,
      isGuestModeActive: false
    });

    await newUser.save();
    
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
    
    const userRecords = await User.find({ email }).select('+password');
    if (userRecords.length === 0) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    const validPassword = await bcrypt.compare(password, userRecords[0].password);
    if (!validPassword) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    let matchedUser = userRecords.find(u => u.vehicleId === vId);
    
    if (!matchedUser) {
      matchedUser = new User({
        name: userRecords[0].name,
        email: userRecords[0].email,
        password: userRecords[0].password,
        isEmailVerified: true,
        vehicleId: vId,
        isGuestModeActive: false
      });
      await matchedUser.save();
      console.log(`Cloned new user record for Car Plate Number: ${vId}`);
    }

    const safeUser = matchedUser.toObject();
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

    res.status(200).json({ message: 'Vehicle claimed successfully' });
  } catch (error) {
    console.error('Claim vehicle error:', error);
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
      
      if (process.env.EMAIL_USER && process.env.EMAIL_USER !== 'your_gmail@gmail.com') {
        try {
          await transporter.sendMail({
            from: `"DrowsySync Analytics" <${process.env.EMAIL_USER}>`,
            to: user.email,
            subject: 'Your DrowsySync Fatigue Report',
            text: 'Attached is your requested driver fatigue summary report.',
            attachments: [
              {
                filename: 'FatigueReport.pdf',
                content: pdfData
              }
            ]
          });
          res.status(200).json({ message: 'Report emailed successfully' });
        } catch (mailError) {
          console.error('❌ Failed to send report email:', mailError);
          res.status(500).json({ error: 'Failed to send report email' });
        }
      } else {
        res.status(200).json({ message: 'Email config missing. PDF generated but not sent.' });
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
    const log = await FatigueLog.findOne({ vehicleId }).sort({ createdAt: -1 });
    
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
    
    // First, try to find the user who actively claimed the Car Plate Number
    let matchedUser = await User.findOne({ vehicleId, isCurrentlyDriving: true });
    
    // If no one is actively driving, fallback to the first registered owner
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
    
    console.log('📥 Received and logged fatigue event for Car Plate Number', vehicleId, ':', fatigueData.status);
    res.status(201).json({ message: 'Event successfully logged', data: logEntry });
  } catch (error) {
    console.error('Error logging event:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
};

// Route for the new /api/logs endpoint
app.post('/api/logs', handleLogIngestion);



// Start the server
app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server is running on http://0.0.0.0:${PORT}`);
});
