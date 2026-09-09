const mongoose = require('mongoose');

// Represents a physical DrowsySync Raspberry Pi unit.
// Each Pi registers itself using its CPU serial (DEVICE_ID from /proc/cpuinfo).
const deviceSchema = new mongoose.Schema({
  deviceId: { type: String, required: true, unique: true }, // Pi CPU serial
  pairedUserId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
  sessionActive: { type: Boolean, default: false },
  // WiFi status — reported by Pi on every heartbeat
  currentWifi: { type: String, default: null },             // e.g. "Afifah's iPhone"
  localIp: { type: String, default: null },                 // e.g. "192.168.43.105"
  // Online presence
  lastSeen: { type: Date, default: null },
  isOnline: { type: Boolean, default: false },
}, { timestamps: true });

module.exports = mongoose.model('Device', deviceSchema);
