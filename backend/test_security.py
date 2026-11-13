import os
import unittest
from unittest.mock import patch

os.environ["AGENT_WORKER_TOKEN"] = "test-token-that-is-long-enough-123"
import server
from fastapi import HTTPException
from pydantic import ValidationError


class BackendSecurityTest(unittest.TestCase):
    def setUp(self):
        server.JOBS.clear()
        server.SUBMISSIONS.clear()
        self.auth = "Bearer " + os.environ["AGENT_WORKER_TOKEN"]

    def test_auth_rejects_missing_scheme_and_wrong_token(self):
        for header in ("", os.environ["AGENT_WORKER_TOKEN"], "Bearer change-me", "Bearer wrong"):
            with self.subTest(header=header), self.assertRaises(HTTPException):
                server.require_auth(header)
        self.assertIsNone(server.require_auth(self.auth))

    def test_request_size_is_bounded(self):
        with self.assertRaises(ValidationError):
            server.SubmitJobRequest(kind="a", params_json="x" * 16385)

    @patch("server.threading.Thread")
    def test_submission_rate_limit(self, thread):
        request = server.SubmitJobRequest(kind="test", params_json="{}")
        for _ in range(30):
            server.submit_job(request, self.auth)
        with self.assertRaises(HTTPException) as raised:
            server.submit_job(request, self.auth)
        self.assertEqual(429, raised.exception.status_code)

    @patch("server.threading.Thread")
    def test_queue_limit_refuses_when_all_jobs_active(self, thread):
        for i in range(server.MAX_JOBS):
            server.JOBS[str(i)] = server.Job3D(id=str(i), kind="test", params_json="{}")
        with self.assertRaises(HTTPException) as raised:
            server.submit_job(server.SubmitJobRequest(kind="test", params_json="{}"), self.auth)
        self.assertEqual(503, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
