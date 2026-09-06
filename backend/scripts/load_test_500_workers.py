"""
Load Testing & Stress Simulation: 500 Concurrent Wearable Sync Workers.

Simulates 500 concurrent Android Health Connect sync clients posting batches
to evaluate throughput, latency distribution (p50, p95, p99), rate-limit handling,
and database connection pool resilience under peak sync burst conditions.
"""

import asyncio
import time
import uuid
from datetime import datetime, timezone, timedelta
from typing import List, Dict, Any
import numpy as np
import httpx
from jose import jwt

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))

from app.core.config import settings

# Configuration
API_BASE_URL = "http://localhost:8000"
TOTAL_WORKERS = 500
CONCURRENCY_LIMIT = 50  # Max active concurrent HTTP connections
SECRET_KEY = settings.SECRET_KEY



async def ensure_workers_in_db() -> None:
    from app.db.session import async_session_factory
    from app.models.user import User
    from app.models.device import Device, WearableSource
    from sqlalchemy.dialects.postgresql import insert
    
    print("Provisioning/verifying 500 test worker accounts, devices & sources in database...")
    users = []
    devices = []
    sources = []
    for i in range(TOTAL_WORKERS):
        uid = uuid.uuid5(uuid.NAMESPACE_DNS, f"worker_{i}.healthos.test")
        did = uuid.uuid5(uuid.NAMESPACE_DNS, f"device_{i}.healthos.test")
        sid = uuid.uuid5(uuid.NAMESPACE_DNS, f"source_{i}.healthos.test")
        users.append({
            "id": uid,
            "email": f"worker_{i}@healthos.test",
            "hashed_password": "hashed_loadtest_pwd",
            "full_name": f"Worker {i}",
            "is_active": True,
            "timezone": "Asia/Kolkata",
            "notification_prefs": {}
        })
        devices.append({
            "id": did,
            "user_id": uid,
            "device_type": "watch",
            "brand": "Samsung",
            "model": "Galaxy Watch 6",
            "os_version": "Wear OS 4.0"
        })
        sources.append({
            "id": sid,
            "user_id": uid,
            "device_id": did,
            "adapter_type": "health_connect",
            "reliability_tier": "official",
            "auth_status": "ACTIVE"
        })

    async with async_session_factory() as session:
        for chunk in [users[k:k+100] for k in range(0, len(users), 100)]:
            await session.execute(insert(User).values(chunk).on_conflict_do_nothing(index_elements=["id"]))
        for chunk in [devices[k:k+100] for k in range(0, len(devices), 100)]:
            await session.execute(insert(Device).values(chunk).on_conflict_do_nothing(index_elements=["id"]))
        for chunk in [sources[k:k+100] for k in range(0, len(sources), 100)]:
            await session.execute(insert(WearableSource).values(chunk).on_conflict_do_nothing(index_elements=["id"]))
        await session.commit()
    print("500 worker accounts, devices & sources verified in database.")


def generate_worker_token(worker_idx: int) -> str:
    user_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"worker_{worker_idx}.healthos.test"))
    payload = {"sub": user_id, "exp": int(time.time()) + 3600}
    return jwt.encode(payload, SECRET_KEY, algorithm="HS256")


def generate_batch_payload(worker_idx: int) -> Dict[str, Any]:
    now = datetime.now(timezone.utc)
    source_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"source_{worker_idx}.healthos.test"))

    measurements = []
    # 5 measurements per batch
    for i in range(5):
        t = (now - timedelta(minutes=i)).isoformat()
        measurements.append({
            "source_record_id": f"loadtest_rec_{worker_idx}_{i}_{uuid.uuid4().hex[:6]}",
            "metric_type": "heart_rate",
            "value": float(np.random.randint(58, 85)),
            "unit": "bpm",
            "recorded_at": t,
            "confidence": 0.98,
            "data_quality_flag": "nominal"
        })

    return {
        "sync_batch_id": str(uuid.uuid4()),
        "source_id": source_id,
        "client_sync_timestamp": now.isoformat(),
        "measurements": measurements
    }


async def run_worker(
    worker_idx: int,
    client: httpx.AsyncClient,
    semaphore: asyncio.Semaphore,
    results: List[Dict[str, Any]]
) -> None:
    token = generate_worker_token(worker_idx)
    headers = {
        "Authorization": f"Bearer {token}",
        "Idempotency-Key": str(uuid.uuid4()),
        "Content-Type": "application/json",
        "X-Correlation-ID": f"loadtest_worker_{worker_idx}_{uuid.uuid4().hex[:6]}"
    }
    payload = generate_batch_payload(worker_idx)


    async with semaphore:
        start_time = time.perf_counter()
        status_code = 0
        error_msg = None
        try:
            resp = await client.post(
                f"{API_BASE_URL}/v1/sync/batch",
                json=payload,
                headers=headers,
                timeout=15.0
            )
            status_code = resp.status_code
        except Exception as e:
            status_code = 0
            error_msg = str(e)
        duration_ms = (time.perf_counter() - start_time) * 1000.0

        results.append({
            "worker_idx": worker_idx,
            "status_code": status_code,
            "duration_ms": duration_ms,
            "error": error_msg
        })


async def main() -> None:
    print("==================================================")
    print(f" Personal Health OS - Wearable Sync Load Test")
    print(f" Target Endpoint: {API_BASE_URL}/v1/sync/batch")
    print(f" Total Workers:   {TOTAL_WORKERS}")
    print(f" Max Concurrency: {CONCURRENCY_LIMIT}")
    print("==================================================")

    semaphore = asyncio.Semaphore(CONCURRENCY_LIMIT)
    results: List[Dict[str, Any]] = []

    limits = httpx.Limits(max_keepalive_connections=50, max_connections=100)
    async with httpx.AsyncClient(limits=limits) as client:
        # Pre-check API health
        try:
            health = await client.get(f"{API_BASE_URL}/health", timeout=3.0)
            if health.status_code != 200:
                print(f"API health check failed with status {health.status_code}")
                return
        except Exception as e:
            print(f"Cannot connect to {API_BASE_URL}: {e}")
        await ensure_workers_in_db()

        overall_start = time.perf_counter()

        tasks = [
            run_worker(i, client, semaphore, results)
            for i in range(TOTAL_WORKERS)
        ]
        await asyncio.gather(*tasks)
        total_time_s = time.perf_counter() - overall_start

    # Metrics calculation
    status_codes = [r["status_code"] for r in results]
    latencies = [r["duration_ms"] for r in results]
    success_count = sum(1 for c in status_codes if c in (200, 201))
    rate_limited_count = sum(1 for c in status_codes if c == 429)
    error_count = sum(1 for c in status_codes if c not in (200, 201, 429))

    p50 = float(np.percentile(latencies, 50))
    p90 = float(np.percentile(latencies, 90))
    p95 = float(np.percentile(latencies, 95))
    p99 = float(np.percentile(latencies, 99))
    avg_latency = float(np.mean(latencies))
    rps = len(results) / total_time_s if total_time_s > 0 else 0

    print("\n==================================================")
    print(" LOAD TEST RESULTS SUMMARY")
    print("==================================================")
    print(f" Total Requests:       {len(results)}")
    print(f" Total Time:           {total_time_s:.2f} s")
    print(f" Throughput:           {rps:.2f} req/s")
    print(f" Successful (200/201): {success_count} ({success_count/len(results)*100:.1f}%)")
    print(f" Rate Limited (429):   {rate_limited_count} ({rate_limited_count/len(results)*100:.1f}%)")
    print(f" Failed/Errors:        {error_count} ({error_count/len(results)*100:.1f}%)")
    print("--------------------------------------------------")
    print(" Latency Distribution:")
    print(f"   p50 (Median):       {p50:.2f} ms")
    print(f"   p90:                {p90:.2f} ms")
    print(f"   p95:                {p95:.2f} ms")
    print(f"   p99:                {p99:.2f} ms")
    print(f"   Average:            {avg_latency:.2f} ms")
    print("==================================================")


if __name__ == "__main__":
    asyncio.run(main())
