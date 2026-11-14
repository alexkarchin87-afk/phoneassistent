import hashlib
import io
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import prepare_vosk_model as model


def archive(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    return result.getvalue()


class ModelPreparationTest(unittest.TestCase):
    def prepare(self, data, root, checksum=None):
        assets = pathlib.Path(root) / "assets"
        with (patch.object(model, "ASSETS", assets),
              patch.object(model, "DEST", assets / "model-ru-small"),
              patch.object(model, "SHA256", checksum or hashlib.sha256(data).hexdigest()),
              patch.object(model.urllib.request, "urlopen", return_value=io.BytesIO(data))):
            model.main()
        return assets / "model-ru-small"

    def test_valid_model_gets_uuid_and_required_files(self):
        data = archive({
            model.NAME + "/am/final.mdl": "model",
            model.NAME + "/conf/model.conf": "config",
        })
        with tempfile.TemporaryDirectory() as temp:
            target = self.prepare(data, temp)
            self.assertTrue((target / "uuid").is_file())
            self.assertTrue((target / "am/final.mdl").is_file())

    def test_archive_with_parent_traversal_is_rejected(self):
        data = archive({model.NAME + "/../../outside": "malicious"})
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                self.prepare(data, temp)
            self.assertFalse((pathlib.Path(temp) / "outside").exists())

    def test_checksum_failure_preserves_existing_model(self):
        with tempfile.TemporaryDirectory() as temp:
            target = pathlib.Path(temp) / "assets/model-ru-small"
            target.mkdir(parents=True)
            (target / "existing").write_text("keep")
            with self.assertRaises(ValueError):
                self.prepare(b"incorrect", temp, checksum="0" * 64)
            self.assertEqual("keep", (target / "existing").read_text())


if __name__ == "__main__":
    unittest.main()
