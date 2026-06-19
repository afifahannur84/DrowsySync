const mongoose = require('mongoose');

const verificationSchema = new mongoose.Schema({
  email: { type: String, required: true },
  vehicleId: { type: String, required: true },
  name: { type: String, required: true },
  phone: { type: String, default: "" },
  licenseSerial: { type: String, default: "" },
  emergencyName: { type: String, default: "" },
  emergencyPhone: { type: String, default: "" },
  password: { type: String, required: true },
  code: { type: String, required: true },
  createdAt: { type: Date, default: Date.now, expires: 600 }
});

module.exports = mongoose.model('Verification', verificationSchema);
