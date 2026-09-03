"""LangSmith Tracing and Run Metadata Configuration."""

import os
from typing import Dict, Any
from app.core.config import settings


def configure_langsmith() -> None:
    """Configures LangSmith environment for LangGraph run tracing."""
    if settings.LANGCHAIN_TRACING_V2:
        os.environ["LANGCHAIN_TRACING_V2"] = "true"
        if settings.LANGCHAIN_API_KEY:
            os.environ["LANGCHAIN_API_KEY"] = settings.LANGCHAIN_API_KEY
        os.environ["LANGCHAIN_PROJECT"] = settings.LANGCHAIN_PROJECT
        os.environ["LANGCHAIN_ENDPOINT"] = settings.LANGCHAIN_ENDPOINT


def get_langsmith_run_config(graph_name: str, user_id: str, severity: str) -> Dict[str, Any]:
    """Generates standard run configuration with tags and metadata for LangSmith."""
    return {
        "tags": ["personal-health-os", graph_name, severity],
        "metadata": {
            "user_id_hash": str(hash(user_id)),
            "graph_name": graph_name,
            "version": "0.1.0"
        }
    }
