"""
Minimal job queue backend for VoiceAgent's ThreeDOrchestrator / RemoteWorkerClient.
Runs on a machine with real compute (GPU workstation, VPS) at AGENT_WORKER_URL.
The phone submits jobs, this service (or a worker process reading from the same
store) executes them and updates status; the phone polls GET /jobs/{id}.

This is intentionally a thin reference implementation — plug in your own executor
(Blender headless render, a game-streaming session, etc.) inside `run_job_stub`.
"""
import os
import uuid
import time
import threading
import hmac
import json
from collections import deque, OrderedDict
from concurrent.futures import ThreadPoolExecutor
from enum import Enum
from typing import Dict, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

# Подхватывает backend/.env при прямом запуске (uvicorn server:app --app-dir backend).
# При запуске через docker-compose эту же переменную подставляет сам compose
# (см. docker-compose.yml) — load_dotenv() тут не мешает, просто не находит
# файл внутри контейнера и молча ничего не делает.
load_dotenv()

WORKER_TOKEN = os.environ.get("AGENT_WORKER_TOKEN", "")
if len(WORKER_TOKEN) < 24 or WORKER_TOKEN == "change-me":
    raise RuntimeError("Set AGENT_WORKER_TOKEN to a unique secret of at least 24 characters")

app = FastAPI(title="VoiceAgent Worker")


class Job3DStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"


class Job3D(BaseModel):
    id: str
    kind: str
    params_json: str
    status: Job3DStatus = Job3DStatus.QUEUED
    result_url: Optional[str] = None
    error_message: Optional[str] = None


class SubmitJobRequest(BaseModel):
    kind: str = Field(min_length=1, max_length=100)
    params_json: str = Field(max_length=16384)

    @field_validator("params_json")
    @classmethod
    def params_must_be_json_object(cls, value: str) -> str:
        try:
            data = json.loads(value, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("invalid constant")))
        except (ValueError, TypeError) as exc:
            raise ValueError("params_json must contain valid JSON") from exc
        if not isinstance(data, dict):
            raise ValueError("params_json must contain a JSON object")
        return value


JOBS: Dict[str, Job3D] = {}
JOBS_LOCK = threading.Lock()
SUBMISSIONS = deque()
MAX_JOBS = 100
EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="voiceagent-worker")
REQUESTS_LOCK = threading.Lock()
REQUESTS = OrderedDict()
MAX_TRACKED_CLIENTS = 2048
MAX_REQUESTS_PER_MINUTE = 120


@app.middleware("http")
async def limit_job_requests(request: Request, call_next):
    # Covers rejected credentials and polling too. This is per-process and uses
    # the peer address, not untrusted forwarded headers; proxies need their own limits.
    if request.url.path == "/jobs" or request.url.path.startswith("/jobs/"):
        client = request.client.host if request.client else "unknown"
        now = time.monotonic()
        with REQUESTS_LOCK:
            while REQUESTS:
                oldest = next(iter(REQUESTS))
                if REQUESTS[oldest] and REQUESTS[oldest][-1] > now - 60:
                    break
                REQUESTS.popitem(last=False)
            if client not in REQUESTS:
                if len(REQUESTS) >= MAX_TRACKED_CLIENTS:
                    REQUESTS.popitem(last=False)
                REQUESTS[client] = deque()
            times = REQUESTS[client]
            while times and times[0] <= now - 60:
                times.popleft()
            if len(times) >= MAX_REQUESTS_PER_MINUTE:
                return JSONResponse(status_code=429, content={"detail": "too many requests"})
            times.append(now)
            REQUESTS.move_to_end(client)
    return await call_next(request)


def require_auth(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="invalid worker token")
    token = authorization[len("Bearer "):].strip()
    if not hmac.compare_digest(token.encode("utf-8"), WORKER_TOKEN.encode("utf-8")):
        raise HTTPException(status_code=401, detail="invalid worker token")


def run_job_stub(job_id: str):
    """Fail honestly until an actual rendering/worker implementation is connected."""
    with JOBS_LOCK:
        job = JOBS[job_id]
        job.status = Job3DStatus.FAILED
        job.result_url = None
        job.error_message = "No worker is configured for this job."


@app.post("/jobs", response_model=Job3D)
def submit_job(req: SubmitJobRequest, authorization: str = Header(default="")):
    require_auth(authorization)
    with JOBS_LOCK:
        now = time.monotonic()
        while SUBMISSIONS and SUBMISSIONS[0] <= now - 60:
            SUBMISSIONS.popleft()
        if len(SUBMISSIONS) >= 30:
            raise HTTPException(status_code=429, detail="too many jobs")
        if len(JOBS) >= MAX_JOBS:
            finished = next((key for key, value in JOBS.items()
                             if value.status in (Job3DStatus.DONE, Job3DStatus.FAILED)), None)
            if finished is None:
                raise HTTPException(status_code=503, detail="job queue full")
            del JOBS[finished]
        SUBMISSIONS.append(now)
        job = Job3D(id=str(uuid.uuid4()), kind=req.kind, params_json=req.params_json)
        JOBS[job.id] = job
        snapshot = job.model_copy(deep=True)
    EXECUTOR.submit(run_job_stub, job.id)
    return snapshot


@app.get("/jobs/{job_id}", response_model=Job3D)
def get_job(job_id: str, authorization: str = Header(default="")):
    require_auth(authorization)
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if job is not None:
            job = job.model_copy(deep=True)
    if job is None:
        raise HTTPException(status_code=404, detail="job not found")
    return job


@app.get("/health")
def health():
    return {"ok": True}

