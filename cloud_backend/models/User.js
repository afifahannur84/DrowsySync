const mongoose = require('mongoose');

const userSchema = new mongoose.Schema({
  name: { type: String, required: true },
  email: { type: String, required: true, unique: true },
  phone: { type: String, default: "" },
  licenseSerial: { type: String, default: "" },
  emergencyName: { type: String, default: "" },
  emergencyPhone: { type: String, default: "" },
  password: { type: String, required: true, select: false },
  isEmailVerified: { type: Boolean, default: false },
  verificationCode: { type: String },
  vehicleId: { type: String, default: "UTEM_LOG_862B" },
  isGuestModeActive: { type: Boolean, default: false },
  isCurrentlyDriving: { type: Boolean, default: false },
  alarmDismissed: { type: Boolean, default: false },
  sessionActive: { type: Boolean, default: false },       // Controls Python camera (STANDBY vs MONITORING)
  sessionResetPending: { type: Boolean, default: false }  // Signals Python to full_reset() counters
}, { timestamps: true });

module.exports = mongoose.model('User', userSchema);
