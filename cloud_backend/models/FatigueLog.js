const mongoose = require('mongoose');

const fatigueLogSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User' },
  vehicleId: { type: String, required: true },
  stage: { type: Number, required: true },
  status: { type: String, required: true },
  perclos: { type: Number, required: true },
  ear: { type: Number, required: true },
  mar: { type: Number, required: true },
  recent_yawn_count: { type: Number, required: true },
  microsleep_active: { type: Boolean, required: true },
  stage3_latched: { type: Boolean, required: true },
  timestamp: { type: Number, required: true }
}, { timestamps: true });

module.exports = mongoose.model('FatigueLog', fatigueLogSchema);
