"""Chat model factory. Provider is chosen by FITAGENT_MODEL ("provider:model")."""

from __future__ import annotations

import logging
import os

from langchain_core.language_models import BaseChatModel

log = logging.getLogger(__name__)

DEFAULT_MODEL = "anthropic:claude-sonnet-5-5"
_KEY_ENV = {
    "anthropic": "ANTHROPIC_API_KEY",
    "openai": "OPENAI_API_KEY",
    "google_genai": "GOOGLE_API_KEY",
}


def get_chat_model(model: str | None = None) -> BaseChatModel | None:
    """Returns a configured chat model, or None when no API key is available.
    Callers must treat None as "use deterministic fallbacks" — the app keeps working without an LLM."""
    spec = model or os.getenv("FITAGENT_MODEL", DEFAULT_MODEL)
    provider = spec.split(":", 1)[0]
    key_env = _KEY_ENV.get(provider)
    if key_env and not os.getenv(key_env):
        log.warning("LLM disabled: %s is not set (model %s). Using rule-based coaching.", key_env, spec)
        return None
    from langchain.chat_models import init_chat_model

    return init_chat_model(spec, max_tokens=1500, timeout=45, max_retries=2)
