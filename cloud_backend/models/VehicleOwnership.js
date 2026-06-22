const mongoose = require('mongoose');

const vehicleOwnershipSchema = new mongoose.Schema({
  userId:        { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  vehicleId:     { type: String, required: true },
  isActive:      { type: Boolean, default: true },
  activatedAt:   { type: Date, default: Date.now },
  deactivatedAt: { type: Date, default: null }
});

// Compound index: quick lookup for "who currently owns this plate?"
vehicleOwnershipSchema.index({ vehicleId: 1, isActive: 1 });
// Quick lookup for "what plates does this user own (or has owned)?"
vehicleOwnershipSchema.index({ userId: 1, isActive: 1 });

module.exports = mongoose.model('VehicleOwnership', vehicleOwnershipSchema);
