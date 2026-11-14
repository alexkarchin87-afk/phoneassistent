#!/usr/bin/env python3
"""Download the pinned, Apache-2.0 Russian Vosk model for Android builds."""
import hashlib
import pathlib
import shutil
import stat
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
DEST = ASSETS / "model-ru-small"
NAME = "vosk-model-small-ru-0.22"
URL = f"https://alphacephei.com/vosk/models/{NAME}.zip"
SHA256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
MAX_DOWNLOAD = 70 * 1024 * 1024
MAX_EXPANDED = 250 * 1024 * 1024


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ASSETS) as temporary:
        tmp = pathlib.Path(temporary)
        archive = tmp / "model.zip"
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(URL, timeout=90) as response, archive.open("wb") as out:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_DOWNLOAD:
                    raise ValueError("Model archive exceeds download size limit")
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != SHA256:
            raise ValueError("Model archive SHA-256 does not match the pinned value")

        with zipfile.ZipFile(archive) as model_zip:
            members = model_zip.infolist()
            if sum(member.file_size for member in members) > MAX_EXPANDED:
                raise ValueError("Model archive expands beyond the size limit")
            for member in members:
                path = pathlib.PurePosixPath(member.filename)
                if (path.is_absolute() or ".." in path.parts or not path.parts
                        or path.parts[0] != NAME
                        or stat.S_ISLNK(member.external_attr >> 16)):
                    raise ValueError(f"Unsafe model archive entry: {member.filename}")
            model_zip.extractall(tmp / "extract")

        extracted = tmp / "extract" / NAME
        for relative in ("am/final.mdl", "conf/model.conf"):
            if not (extracted / relative).is_file():
                raise ValueError(f"Model file missing: {relative}")
        (extracted / "uuid").write_text(NAME + "-" + SHA256 + "\n", encoding="utf-8")
        if DEST.exists():
            shutil.rmtree(DEST)
        shutil.move(str(extracted), DEST)
    print(f"Prepared {NAME}; archive SHA-256: {SHA256}")


if __name__ == "__main__":
    main()
