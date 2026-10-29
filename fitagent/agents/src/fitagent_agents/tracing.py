"""LangSmith tracing setup.

LangChain/LangGraph trace automatically when LANGSMITH_TRACING=true and LANGSMITH_API_KEY are set.
Deterministic steps (readiness, safety, validation) are wrapped with @traceable so a single trace
shows both model calls and the rules that shaped the answer.
"""

from __future__ import annotations

import hashlib
import os

from langchain_core.runnables import RunnableConfig

DEFAULT_PROJECT = "fitagent"


def configure_tracing() -> bool:
    """Enables tracing when an API key is present (unless explicitly disabled). Returns whether it's on."""
    if not os.getenv("LANGSMITH_API_KEY") and os.getenv("LANGCHAIN_API_KEY"):  # legacy variable name
        os.environ["LANGSMITH_API_KEY"] = os.environ["LANGCHAIN_API_KEY"]
    if not os.getenv("LANGSMITH_API_KEY"):
        os.environ["LANGSMITH_TRACING"] = "false"
        return False
    os.environ.setdefault("LANGSMITH_TRACING", "true")
    os.environ.setdefault("LANGSMITH_PROJECT", DEFAULT_PROJECT)
    return os.environ["LANGSMITH_TRACING"].lower() == "true"


def pseudonymous_id(user_id: str) -> str:
    """Traces carry a stable hash, never the raw account id."""
    return hashlib.sha256(user_id.encode()).hexdigest()[:16]


def run_config(run_name: str, user_id: str, **metadata: object) -> RunnableConfig:
    return RunnableConfig(
        run_name=run_name,
        tags=["fitagent", run_name],
        metadata={"user": pseudonymous_id(user_id), **metadata},
        recursion_limit=12,
    )
