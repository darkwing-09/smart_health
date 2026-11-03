from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from typing import Literal

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError

from .config import get_settings

_hasher = PasswordHasher()
TokenType = Literal["access", "refresh"]
ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return _hasher.verify(password_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


# Constant-time dummy check so unknown emails take as long as wrong passwords.
_DUMMY_HASH = _hasher.hash("timing-equaliser")


def burn_verify() -> None:
    verify_password("x", _DUMMY_HASH)


def create_token(user_id: str, token_version: int, kind: TokenType) -> str:
    s = get_settings()
    lifetime = timedelta(minutes=s.access_token_minutes) if kind == "access" else timedelta(days=s.refresh_token_days)
    now = datetime.now(UTC)
    payload = {"sub": user_id, "ver": token_version, "typ": kind, "iat": now, "exp": now + lifetime,
               "jti": uuid.uuid4().hex}
    return jwt.encode(payload, s.secret_key, algorithm=ALGORITHM)


class TokenError(Exception):
    pass


def decode_token(token: str, kind: TokenType) -> tuple[str, int]:
    """Returns (user_id, token_version) or raises TokenError."""
    try:
        payload = jwt.decode(token, get_settings().secret_key, algorithms=[ALGORITHM],
                             options={"require": ["sub", "exp", "typ", "ver"]})
    except jwt.PyJWTError as exc:
        raise TokenError(str(exc)) from exc
    if payload["typ"] != kind:
        raise TokenError("wrong token type")
    return str(payload["sub"]), int(payload["ver"])
