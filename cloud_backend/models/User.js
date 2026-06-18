const mongoose = require('mongoose');

const userSchema = new mongoose.Schema({
  name: { type: String, required: true },
  email: { type: String, required: true, unique: true },
  phone: { type: String, default: "" },
  password: { type: String, required: true, select: false },
  isEmailVerified: { type: Boolean, default: false },
  verificationCode: { type: String },
  vehicleId: { type: String, default: "UTEM_LOG_862B" },
  isGuestModeActive: { type: Boolean, default: false },
  isCurrentlyDriving: { type: Boolean, default: false }
}, { timestamps: true });

module.exports = mongoose.model('User', userSchema);
