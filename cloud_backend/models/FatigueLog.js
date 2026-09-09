const mongoose = require('mongoose');

const fatigueLogSchema = new mongoose.Schema({
  userId:   { type: mongoose.Schema.Types.ObjectId, ref: 'User' },
  vehicleId: { type: String, default: null },
  deviceId:  { type: String, required: true },          // Pi CPU serial (DEVICE_ID)
  stage:     { type: Number, required: true },
  status:    { type: String, required: true },
  perclos:   { type: Number, required: true },
  ear:       { type: Number, required: true },
  mar:       { type: Number, required: true },
  recent_yawn_count: { type: Number, required: true },
  microsleep_active: { type: Boolean, required: true },
  stage3_latched:    { type: Boolean, required: true },
  timestamp:         { type: Number, required: true },

  // ── GPS / Location (sub-document) ─────────────────────────────────────────
  // Patched by the Android app after the alert fires, via PATCH /api/logs/:id/location.
  // Grouped as a sub-document because lat, lng, and name are a single logical unit.
  location: {
    lat:  { type: Number, default: null },
    lng:  { type: Number, default: null },
    name: { type: String, default: null }              // Reverse-geocoded via Nominatim
  },

  // Guest mode flag
  isGuest: { type: Boolean, default: false }
}, { timestamps: true });

module.exports = mongoose.model('FatigueLog', fatigueLogSchema);
