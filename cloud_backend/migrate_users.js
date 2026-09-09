/**
 * migrate_users.js — DrowsySync One-Time Database Migration
 * ===========================================================
 * Migrates old flat-field user documents to the current nested schema.
 *
 * OLD (stale) structure:
 *   isGuestModeActive, isCurrentlyDriving, alarmDismissed,
 *   sessionActive, sessionResetPending  → flat top-level fields
 *   emergencyName, emergencyPhone       → flat top-level fields
 *   vehicleId                           → stale, now in VehicleOwnership
 *
 * NEW (current) structure:
 *   sessionState: { isGuestModeActive, isCurrentlyDriving, alarmDismissed,
 *                   sessionActive, sessionResetPending }
 *   emergencyContact: { name, phone }
 *
 * Run:
 *   cd cloud_backend
 *   node migrate_users.js
 */

const mongoose = require('mongoose');
const dotenv = require('dotenv');
dotenv.config();

async function main() {
  await mongoose.connect(process.env.MONGO_URI);
  console.log('✅ Connected to MongoDB Atlas\n');

  const db = mongoose.connection.db;
  const users = db.collection('users');

  // Find all documents that still have OLD flat fields
  const oldDocs = await users.find({
    $or: [
      { isGuestModeActive:   { $exists: true } },
      { isCurrentlyDriving:  { $exists: true } },
      { alarmDismissed:      { $exists: true } },
      { sessionActive:       { $exists: true } },
      { sessionResetPending: { $exists: true } },
      { emergencyName:       { $exists: true } },
      { emergencyPhone:      { $exists: true } },
      { vehicleId:           { $exists: true } },
    ]
  }).toArray();

  console.log(`📋 Found ${oldDocs.length} document(s) to migrate.\n`);

  if (oldDocs.length === 0) {
    console.log('✅ Nothing to migrate — all documents are already in current schema format.');
    await mongoose.disconnect();
    return;
  }

  let migrated = 0;
  let skipped  = 0;

  for (const doc of oldDocs) {
    console.log(`🔄 Migrating user: ${doc.email} (${doc._id})`);

    const $set   = {};
    const $unset = {};

    // ── 1. Session state flags → sessionState sub-document ─────────────────
    const currentSessionState = doc.sessionState || {};

    if (doc.isGuestModeActive !== undefined && currentSessionState.isGuestModeActive === undefined) {
      $set['sessionState.isGuestModeActive'] = doc.isGuestModeActive;
      $unset['isGuestModeActive'] = '';
    }
    if (doc.isCurrentlyDriving !== undefined && currentSessionState.isCurrentlyDriving === undefined) {
      $set['sessionState.isCurrentlyDriving'] = doc.isCurrentlyDriving;
      $unset['isCurrentlyDriving'] = '';
    }
    if (doc.alarmDismissed !== undefined && currentSessionState.alarmDismissed === undefined) {
      $set['sessionState.alarmDismissed'] = doc.alarmDismissed;
      $unset['alarmDismissed'] = '';
    }
    if (doc.sessionActive !== undefined && currentSessionState.sessionActive === undefined) {
      $set['sessionState.sessionActive'] = doc.sessionActive;
      $unset['sessionActive'] = '';
    }
    if (doc.sessionResetPending !== undefined && currentSessionState.sessionResetPending === undefined) {
      $set['sessionState.sessionResetPending'] = doc.sessionResetPending;
      $unset['sessionResetPending'] = '';
    }

    // ── 2. Emergency contact → emergencyContact sub-document ───────────────
    const currentEmergency = doc.emergencyContact || {};

    if (doc.emergencyName !== undefined && currentEmergency.name === undefined) {
      $set['emergencyContact.name'] = doc.emergencyName;
      $unset['emergencyName'] = '';
    }
    if (doc.emergencyPhone !== undefined && currentEmergency.phone === undefined) {
      $set['emergencyContact.phone'] = doc.emergencyPhone;
      $unset['emergencyPhone'] = '';
    }

    // ── 3. Remove stale vehicleId from user document ────────────────────────
    if (doc.vehicleId !== undefined) {
      $unset['vehicleId'] = '';
      console.log(`   Removing stale vehicleId: "${doc.vehicleId}" (now in VehicleOwnership)`);
    }

    if (Object.keys($set).length > 0 || Object.keys($unset).length > 0) {
      const updateOp = {};
      if (Object.keys($set).length > 0)   updateOp.$set   = $set;
      if (Object.keys($unset).length > 0) updateOp.$unset = $unset;

      await users.updateOne({ _id: doc._id }, updateOp);
      console.log(`   ✅ Migrated successfully.\n`);
      migrated++;
    } else {
      console.log(`   ⏭️  Already up to date.\n`);
      skipped++;
    }
  }

  console.log('═'.repeat(50));
  console.log(`Migration complete:`);
  console.log(`  ✅ Migrated : ${migrated} document(s)`);
  console.log(`  ⏭️  Skipped  : ${skipped} document(s)`);
  console.log('═'.repeat(50));

  await mongoose.disconnect();
  console.log('\n✅ Disconnected. Migration done.');
}

main().catch(err => {
  console.error('❌ Migration failed:', err);
  mongoose.disconnect();
  process.exit(1);
});
