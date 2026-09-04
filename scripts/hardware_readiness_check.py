#!/usr/bin/env python3
"""
Hardware & Environment Readiness Check for Personal Health OS Production Pilot.
Evaluates physical hardware, mobile SDK, connected devices, Health Connect availability,
FCM credentials, and backend infrastructure reachability.

Usage:
    python scripts/hardware_readiness_check.py [--json]
"""

import sys
import os
import json
import subprocess
import asyncio
from pathlib import Path
from typing import Dict, Any

# Ensure project root is in path
ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

try:
    import asyncpg
except ImportError:
    asyncpg = None

try:
    import redis.asyncio as aioredis
except ImportError:
    aioredis = None


def check_command(cmd: list[str]) -> tuple[bool, str]:
    """Execute command and return success status and stdout/stderr."""
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        return (res.returncode == 0, res.stdout.strip() if res.returncode == 0 else res.stderr.strip())
    except FileNotFoundError:
        return (False, f"Binary '{cmd[0]}' not found")
    except Exception as e:
        return (False, str(e))


def find_adb() -> str | None:
    """Find adb binary in PATH or Android SDK platform-tools."""
    ok, path = check_command(["which", "adb"])
    if ok and path:
        return path
    sdk_adb = Path("/home/darkwing/Android/Sdk/platform-tools/adb")
    if sdk_adb.exists() and os.access(sdk_adb, os.X_OK):
        return str(sdk_adb)
    user_sdk = Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"
    if user_sdk.exists() and os.access(user_sdk, os.X_OK):
        return str(user_sdk)
    return None


async def check_backend_services() -> Dict[str, Any]:
    """Check live connectivity to PostgreSQL/TimescaleDB and Redis."""
    results = {
        "postgresql": {"status": "BLOCKED", "detail": "Not checked"},
        "redis": {"status": "BLOCKED", "detail": "Not checked"}
    }
    
    # Read .env if present
    env_file = ROOT_DIR / ".env"
    db_url = "postgresql://healthos_user:healthos_dev_password@localhost:5435/healthos_db"
    redis_url = "redis://localhost:6380/0"
    
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if line.startswith("DATABASE_URL="):
                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                # convert asyncpg scheme to standard postgresql for testing if needed
                if "+asyncpg" in val:
                    val = val.replace("+asyncpg", "")
                db_url = val
            elif line.startswith("REDIS_URL="):
                redis_url = line.split("=", 1)[1].strip().strip('"').strip("'")

    # PostgreSQL check
    if asyncpg:
        try:
            conn = await asyncpg.connect(db_url, timeout=5)
            val = await conn.fetchval("SELECT 1")
            rev = await conn.fetchval("SELECT version_num FROM alembic_version")
            await conn.close()
            results["postgresql"] = {
                "status": "VERIFIED",
                "detail": f"Connected to {db_url.split('@')[-1]}. SELECT 1 -> {val}. Alembic revision: {rev}"
            }
        except Exception as e:
            results["postgresql"] = {"status": "BLOCKED", "detail": f"Connection failed: {e}"}
    else:
        results["postgresql"] = {"status": "BLOCKED", "detail": "asyncpg library not installed"}

    # Redis check
    if aioredis:
        try:
            r = aioredis.from_url(redis_url, socket_timeout=5)
            pong = await r.ping()
            await r.aclose()
            results["redis"] = {
                "status": "VERIFIED",
                "detail": f"Connected to {redis_url}. PING -> {pong}"
            }
        except Exception as e:
            results["redis"] = {"status": "BLOCKED", "detail": f"Connection failed: {e}"}
    else:
        results["redis"] = {"status": "BLOCKED", "detail": "redis library not installed"}

    return results


def check_hardware() -> Dict[str, Any]:
    """Run full hardware readiness audit."""
    checks = {}

    # 1. Android SDK
    sdk_path = Path("/home/darkwing/Android/Sdk")
    if not sdk_path.exists():
        sdk_path = Path.home() / "Android" / "Sdk"
    
    if sdk_path.exists():
        platforms = [p.name for p in (sdk_path / "platforms").glob("*")] if (sdk_path / "platforms").exists() else []
        build_tools = [p.name for p in (sdk_path / "build-tools").glob("*")] if (sdk_path / "build-tools").exists() else []
        sys_images = [p.name for p in (sdk_path / "system-images").glob("**/*") if p.is_dir()] if (sdk_path / "system-images").exists() else []
        
        checks["android_sdk"] = {
            "status": "VERIFIED",
            "detail": f"SDK root: {sdk_path} | Platforms: {platforms} | Build-Tools: {build_tools}"
        }
        checks["android_system_images"] = {
            "status": "VERIFIED" if sys_images else "BLOCKED",
            "detail": f"System images found: {sys_images if sys_images else 'None (AVD creation blocked)'}"
        }
    else:
        checks["android_sdk"] = {"status": "BLOCKED", "detail": "Android SDK path not found"}
        checks["android_system_images"] = {"status": "BLOCKED", "detail": "Android SDK not found"}

    # 2. ADB
    adb_bin = find_adb()
    if adb_bin:
        ok, version_str = check_command([adb_bin, "version"])
        first_line = version_str.splitlines()[0] if version_str else "Unknown"
        checks["adb_binary"] = {
            "status": "VERIFIED",
            "detail": f"{adb_bin} ({first_line})"
        }
        
        # 3. Connected devices
        ok, dev_str = check_command([adb_bin, "devices", "-l"])
        attached = []
        if ok and dev_str:
            lines = [line.strip() for line in dev_str.splitlines() if line.strip() and not line.startswith("List of devices")]
            attached = lines
            
        if attached:
            checks["physical_android_device"] = {
                "status": "VERIFIED",
                "detail": f"{len(attached)} device(s) attached: {attached}"
            }
            # Health Connect on device check
            hc_ok, hc_out = check_command([adb_bin, "shell", "pm", "path", "com.google.android.apps.healthdata"])
            checks["health_connect_runtime"] = {
                "status": "VERIFIED" if hc_ok and "package:" in hc_out else "PARTIAL",
                "detail": "Installed via package manager" if hc_ok and "package:" in hc_out else "Not found in pm path (may use Android 14 system framework)"
            }
            # Wearable connection check via bluetooth dumpsys
            bt_ok, bt_out = check_command([adb_bin, "shell", "dumpsys", "bluetooth_manager"])
            checks["wearable_connection"] = {
                "status": "PARTIAL",
                "detail": "Physical device attached; manual wearable pairing verification required"
            }
        else:
            checks["physical_android_device"] = {
                "status": "BLOCKED",
                "detail": "0 attached devices detected via 'adb devices'"
            }
            checks["health_connect_runtime"] = {
                "status": "BLOCKED",
                "detail": "No device/emulator available to query Health Connect IPC"
            }
            checks["wearable_connection"] = {
                "status": "BLOCKED",
                "detail": "No host phone available for Bluetooth wearable bridge"
            }
    else:
        checks["adb_binary"] = {"status": "BLOCKED", "detail": "adb not found in PATH or standard SDK paths"}
        checks["physical_android_device"] = {"status": "BLOCKED", "detail": "adb missing"}
        checks["health_connect_runtime"] = {"status": "BLOCKED", "detail": "adb missing"}
        checks["wearable_connection"] = {"status": "BLOCKED", "detail": "adb missing"}

    # 4. FCM Credentials
    fcm_cred = os.getenv("FCM_CREDENTIALS_JSON", "")
    fcm_project = os.getenv("FCM_PROJECT_ID", "")
    if not fcm_cred and (ROOT_DIR / ".env").exists():
        for line in (ROOT_DIR / ".env").read_text().splitlines():
            if line.startswith("FCM_CREDENTIALS_JSON="):
                fcm_cred = line.split("=", 1)[1].strip().strip('"').strip("'")
            elif line.startswith("FCM_PROJECT_ID="):
                fcm_project = line.split("=", 1)[1].strip().strip('"').strip("'")

    if fcm_cred and Path(fcm_cred).exists():
        checks["fcm_credentials"] = {
            "status": "VERIFIED",
            "detail": f"Service account file present at {fcm_cred} (Project: {fcm_project})"
        }
    else:
        checks["fcm_credentials"] = {
            "status": "PARTIAL",
            "detail": f"FCM_CREDENTIALS_JSON not provided. Running in deterministic dry-run simulation mode (Project: {fcm_project or 'personal-health-os-dev'})"
        }

    return checks


async def main_async():
    json_mode = "--json" in sys.argv
    hw_checks = check_hardware()
    backend_checks = await check_backend_services()
    
    all_checks = {**hw_checks, **backend_checks}

    if json_mode:
        print(json.dumps(all_checks, indent=2))
        return

    print("\n" + "="*80)
    print("PERSONAL HEALTH OS — HARDWARE & ENVIRONMENT READINESS REPORT")
    print("="*80)
    print(f"{'CHECK':<28} | {'STATUS':<10} | {'DETAILS'}")
    print("-"*80)

    counts = {"VERIFIED": 0, "PARTIAL": 0, "BLOCKED": 0}
    for name, item in all_checks.items():
        status = item["status"]
        counts[status] = counts.get(status, 0) + 1
        status_icon = "✅" if status == "VERIFIED" else ("🟡" if status == "PARTIAL" else "⚠️")
        print(f"{name:<28} | {status_icon} {status:<8} | {item['detail']}")

    print("="*80)
    print(f"Summary: {counts['VERIFIED']} VERIFIED, {counts['PARTIAL']} PARTIAL, {counts['BLOCKED']} BLOCKED")
    print("="*80 + "\n")


if __name__ == "__main__":
    asyncio.run(main_async())
