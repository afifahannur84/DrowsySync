"""
wifi_setup_server.py — DrowsySync Pi WiFi Provisioning Server
==============================================================
Runs ONLY when the Pi cannot connect to a known WiFi network on boot.
In that case, the Pi creates its own hotspot ("DrowsySync-Setup", open / no password)
and this script serves a tiny provisioning API at http://192.168.4.1:80.

The Android app's WifiSetupActivity connects to "DrowsySync-Setup", then:
  1. Calls GET /wifi/scan  → receives a list of nearby SSIDs
  2. Calls POST /wifi/connect { ssid, password } → Pi saves creds & reboots

Usage (called from setup_mode.sh, NOT from the main drowsysync.service):
    sudo python wifi_setup_server.py

Prerequisites (one-time setup on Pi):
    sudo apt install -y hostapd dnsmasq
    pip install flask

Hotspot setup (run once, activates on boot when no WiFi found):
    See: https://www.raspberrypi.com/documentation/computers/configuration.html
    Or use the included setup_mode.sh script.
"""

import subprocess
import sys
import time

from flask import Flask, Response, jsonify, request

app = Flask(__name__)

# ── Helpers ───────────────────────────────────────────────────────────────────

def _scan_networks() -> list[str]:
    """Return a list of visible SSIDs sorted by signal strength."""
    try:
        raw = subprocess.check_output(
            ["sudo", "iwlist", "wlan0", "scan"],
            stderr=subprocess.DEVNULL,
            timeout=10
        ).decode()
        ssids = []
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("ESSID:"):
                ssid = line.split('"')[1]
                if ssid and ssid not in ssids:
                    ssids.append(ssid)
        return ssids
    except Exception as e:
        print(f"[WIFI-SETUP] Scan failed: {e}")
        return []


def _write_wpa_supplicant(ssid: str, password: str) -> None:
    """Write credentials to /etc/wpa_supplicant/wpa_supplicant.conf."""
    config = f"""ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
update_config=1
country=MY

network={{
    ssid="{ssid}"
    psk="{password}"
    key_mgmt=WPA-PSK
}}
"""
    with open("/etc/wpa_supplicant/wpa_supplicant.conf", "w") as f:
        f.write(config)
    print(f"[WIFI-SETUP] Saved credentials for SSID: {ssid}")


# ── API Routes ────────────────────────────────────────────────────────────────

@app.route("/wifi/scan", methods=["GET"])
def wifi_scan():
    """Return list of nearby SSIDs."""
    ssids = _scan_networks()
    return jsonify({"ssids": ssids})


@app.route("/wifi/connect", methods=["POST"])
def wifi_connect():
    """
    Receive { ssid, password } from the Android app.
    Save to wpa_supplicant.conf and schedule a reboot.
    """
    data = request.get_json(silent=True) or {}
    ssid = data.get("ssid", "").strip()
    password = data.get("password", "").strip()

    if not ssid:
        return jsonify({"error": "ssid is required"}), 400

    try:
        _write_wpa_supplicant(ssid, password)
    except Exception as e:
        return jsonify({"error": f"Failed to save credentials: {e}"}), 500

    # Respond BEFORE rebooting so the app gets the success message
    response = jsonify({"ok": True, "message": f"Credentials saved for '{ssid}'. Pi will reboot now."})

    # Reboot after a short delay in a background thread
    def _reboot():
        time.sleep(2)
        subprocess.run(["sudo", "reboot"], check=False)

    import threading
    threading.Thread(target=_reboot, daemon=True).start()

    return response


@app.route("/", methods=["GET"])
def index():
    return jsonify({
        "service": "DrowsySync WiFi Setup",
        "endpoints": {
            "GET /wifi/scan": "Returns list of visible SSIDs",
            "POST /wifi/connect": "Body: {ssid, password} — saves and reboots"
        }
    })


# ── Entry Point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 60)
    print("  DrowsySync WiFi Provisioning Server")
    print("  Listening on http://192.168.4.1:80")
    print("  Connect phone to 'DrowsySync-Setup' hotspot first.")
    print("=" * 60)
    # Run on port 80 at the Pi's hotspot IP (requires sudo)
    app.run(host="0.0.0.0", port=80, debug=False)
