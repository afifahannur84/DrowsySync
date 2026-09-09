const mongoose = require('mongoose');

const userSchema = new mongoose.Schema({
  // ── Core identity ──────────────────────────────────────────────────────────
  name:             { type: String, required: true },
  email:            { type: String, required: true, unique: true },
  phone:            { type: String, default: "" },
  licenseSerial:    { type: String, default: "" },
  password:         { type: String, required: true, select: false },
  isEmailVerified:  { type: Boolean, default: false },
  verificationCode: { type: String },
  pendingEmail:     { type: String },

  // ── Emergency contact (sub-document) ──────────────────────────────────────
  // Grouped here because emergency contact is a property OF the driver profile,
  // not a separate entity that needs its own collection.
  emergencyContact: {
    name:  { type: String, default: "" },
    phone: { type: String, default: "" }
  },

  // ── Live session state (sub-document) ─────────────────────────────────────
  // Volatile real-time flags that control Pi camera and driving session.
  // Kept in users (not a separate collection) to avoid an extra DB round-trip
  // on the Pi's 1.5s session poll.
  sessionState: {
    isGuestModeActive:   { type: Boolean, default: false },
    isCurrentlyDriving:  { type: Boolean, default: false },
    alarmDismissed:      { type: Boolean, default: false },
    sessionActive:       { type: Boolean, default: false },      // Controls Python camera (STANDBY vs MONITORING)
    sessionResetPending: { type: Boolean, default: false }       // Signals Python to full_reset() counters
  }

  // NOTE: vehicleId removed — use VehicleOwnership collection to look up vehicle.
  // NOTE: pairedDeviceId removed — use Device.pairedUserId to look up pairing.
}, { timestamps: true });

module.exports = mongoose.model('User', userSchema);
