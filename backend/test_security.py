import os
import unittest
from unittest.mock import patch

os.environ["AGENT_WORKER_TOKEN"] = "test-token-that-is-long-enough-123"
import server
from fastapi import HTTPException
from pydantic import ValidationError
from fastapi.testclient import TestClient


class BackendSecurityTest(unittest.TestCase):
    def setUp(self):
        server.JOBS.clear()
        server.SUBMISSIONS.clear()
        server.REQUESTS.clear()
        self.auth = "Bearer " + os.environ["AGENT_WORKER_TOKEN"]

    def test_auth_rejects_missing_scheme_and_wrong_token(self):
        for header in ("", os.environ["AGENT_WORKER_TOKEN"], "Bearer change-me", "Bearer wrong"):
            with self.subTest(header=header), self.assertRaises(HTTPException):
                server.require_auth(header)
        self.assertIsNone(server.require_auth(self.auth))

    def test_request_size_is_bounded(self):
        with self.assertRaises(ValidationError):
            server.SubmitJobRequest(kind="a", params_json="x" * 16385)

    def test_params_are_valid_json_objects(self):
        for params in ("{broken", "[]", "null", '{"x": NaN}'):
            with self.subTest(params=params), self.assertRaises(ValidationError):
                server.SubmitJobRequest(kind="test", params_json=params)
        self.assertEqual('{"x": 1}', server.SubmitJobRequest(kind="test", params_json='{"x": 1}').params_json)

    @patch("server.MAX_REQUESTS_PER_MINUTE", 3)
    def test_http_rate_limit_covers_failed_auth_and_polling(self):
        with TestClient(server.app) as client:
            for _ in range(3):
                self.assertEqual(401, client.get("/jobs/example").status_code)
            self.assertEqual(429, client.get("/jobs/example").status_code)

    def test_http_rejects_invalid_json_before_job_is_created(self):
        with TestClient(server.app) as client:
            result = client.post("/jobs", headers={"Authorization": self.auth},
                                 json={"kind": "test", "params_json": "{bad"})
            self.assertEqual(422, result.status_code)
            self.assertEqual(0, len(server.JOBS))

    @patch("server.EXECUTOR.submit")
    def test_submission_rate_limit(self, thread):
        request = server.SubmitJobRequest(kind="test", params_json="{}")
        for _ in range(30):
            server.submit_job(request, self.auth)
        with self.assertRaises(HTTPException) as raised:
            server.submit_job(request, self.auth)
        self.assertEqual(429, raised.exception.status_code)

    def test_unconfigured_worker_never_returns_a_fake_success(self):
        server.JOBS["job"] = server.Job3D(id="job", kind="test", params_json="{}")
        server.run_job_stub("job")
        self.assertEqual(server.Job3DStatus.FAILED, server.JOBS["job"].status)
        self.assertIsNone(server.JOBS["job"].result_url)
        self.assertIn("No worker", server.JOBS["job"].error_message)

    @patch("server.EXECUTOR.submit")
    def test_queue_limit_refuses_when_all_jobs_active(self, thread):
        for i in range(server.MAX_JOBS):
            server.JOBS[str(i)] = server.Job3D(id=str(i), kind="test", params_json="{}")
        with self.assertRaises(HTTPException) as raised:
            server.submit_job(server.SubmitJobRequest(kind="test", params_json="{}"), self.auth)
        self.assertEqual(503, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
