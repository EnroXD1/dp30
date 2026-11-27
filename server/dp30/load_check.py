"""T04: paced traffic from 20 authenticated clients against 10,000 seeded requests."""
import argparse
import asyncio
import json
import math
import os
import ssl
import time
from collections import Counter
from pathlib import Path

import httpx


async def measure(base, ca, seconds):
    ctx = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
    p = os.environ["DP30_DEMO_PASSWORD"]
    async with httpx.AsyncClient(base_url=base, verify=ctx, timeout=8,
                                 limits=httpx.Limits(max_connections=24, max_keepalive_connections=24)) as api:
        async def login(name):
            r = await api.post("/api/v1/auth/login", json={"login": name, "password": p})
            r.raise_for_status()
            return {"Authorization": f"Bearer {r.json()['token']}"}

        dispatcher, worker = await login("dispatcher"), await login("worker")
        begin = time.monotonic()
        deadline = begin + seconds

        async def client(number):
            headers = worker if number % 2 else dispatcher
            durations = []
            outcomes = Counter()
            count = 0
            while time.monotonic() < deadline:
                params = {"limit": 30, "offset": ((count * 53 + number * 11) % 100) * 10}
                if number % 2:
                    params.update(status="ASSIGNED", brigade_id=1)
                elif count % 3 == 0:
                    params.update(number=((count + number) % 10000) + 1, overdue="true")
                    params["offset"] = 0
                start = time.monotonic()
                try:
                    response = await api.get("/api/v1/requests", params=params, headers=headers)
                    outcomes[str(response.status_code)] += 1
                    if response.status_code == 200:
                        durations.append(time.monotonic() - start)
                        if number % 2 and any(card["brigade_id"] != 1 for card in response.json()["items"]):
                            outcomes["scope_violation"] += 1
                except httpx.HTTPError:
                    outcomes["transport_error"] += 1
                count += 1
                await asyncio.sleep(max(0.0, 0.25 - (time.monotonic() - start)))
            return durations, outcomes

        batches = await asyncio.gather(*(client(i) for i in range(20)))
    durations = sorted(x for series, _ in batches for x in series)
    outcomes = sum((counts for _, counts in batches), Counter())
    n = sum(v for k, v in outcomes.items() if k != "scope_violation")
    p95 = durations[math.ceil(.95 * len(durations)) - 1] if durations else None
    rate_5xx = sum(v for k, v in outcomes.items() if k.startswith("5")) / n if n else 1
    return {"target_seconds": seconds, "elapsed_seconds": round(time.monotonic()-begin, 2),
            "clients": 20, "requests": n, "outcomes": dict(outcomes),
            "p95_seconds": round(p95, 4) if p95 is not None else None, "rate_5xx": rate_5xx,
            "numeric_thresholds_met": p95 is not None and p95 <= 2 and rate_5xx <= .01 and n > 0
                and n == outcomes["200"] + sum(v for k,v in outcomes.items() if k.startswith("5"))
                and not outcomes["scope_violation"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("--ca")
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if args.seconds < 1:
        parser.error("--seconds must be positive")
    metrics = asyncio.run(measure(args.base, args.ca, args.seconds))
    content = json.dumps(metrics, indent=2)
    if args.out:
        args.out.write_text(content + "\n", encoding="utf-8")
    print(content)
    raise SystemExit(0 if metrics["numeric_thresholds_met"] else 1)
