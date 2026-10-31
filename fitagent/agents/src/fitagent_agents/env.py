"""Loads the project's `.env` (fitagent/.env) into the process environment.

pydantic-settings only reads `.env` into its own model, but LangChain and LangSmith read API keys straight from
`os.environ`, so the file has to be exported. Existing variables always win, so Docker/CI settings are untouched.
"""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv


def find_env_file(start: Path | None = None) -> Path | None:
    """The nearest `.env` in the start directory or any parent (so it works from backend/ and agents/)."""
    here = (start or Path.cwd()).resolve()
    for directory in (here, *here.parents):
        candidate = directory / ".env"
        if candidate.is_file():
            return candidate
    return None


def load_env(start: Path | None = None) -> Path | None:
    """Exports the nearest `.env`. Set FITAGENT_SKIP_DOTENV=1 to disable (tests do, to stay hermetic)."""
    if os.getenv("FITAGENT_SKIP_DOTENV") == "1":
        return None
    path = find_env_file(start)
    if path:
        load_dotenv(path, override=False)
    return path
