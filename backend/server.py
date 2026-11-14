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
from collections import deque
from enum import Enum
from typing import Dict, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

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


JOBS: Dict[str, Job3D] = {}
JOBS_LOCK = threading.Lock()
SUBMISSIONS = deque()
MAX_JOBS = 100


def require_auth(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="invalid worker token")
    token = authorization[len("Bearer "):].strip()
    if not hmac.compare_digest(token.encode("utf-8"), WORKER_TOKEN.encode("utf-8")):
        raise HTTPException(status_code=401, detail="invalid worker token")


def run_job_stub(job_id: str):
    """Replace this with your real executor. Kept synchronous + backgrounded via thread
    for simplicity; swap for a proper task queue (RQ/Celery) for production use."""
    with JOBS_LOCK:
        job = JOBS[job_id]
        job.status = Job3DStatus.RUNNING
    time.sleep(5)  # simulate work
    with JOBS_LOCK:
        job.status = Job3DStatus.DONE
        job.result_url = f"https://your-vps.example.com/results/{job_id}.png"


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
    threading.Thread(target=run_job_stub, args=(job.id,), daemon=True).start()
    return job


@app.get("/jobs/{job_id}", response_model=Job3D)
def get_job(job_id: str, authorization: str = Header(default="")):
    require_auth(authorization)
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="job not found")
    return job


@app.get("/health")
def health():
    return {"ok": True}

