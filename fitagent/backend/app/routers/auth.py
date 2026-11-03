from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from ..config import get_settings
from ..deps import CurrentUser, SessionDep, client_ip, limiter
from ..models import User
from ..schemas import LoginIn, RefreshIn, RegisterIn, TokenOut
from ..security import TokenError, burn_verify, create_token, decode_token, hash_password, verify_password

router = APIRouter(prefix="/v1/auth", tags=["auth"])


def _tokens(user: User) -> TokenOut:
    return TokenOut(
        access_token=create_token(user.id, user.token_version, "access"),
        refresh_token=create_token(user.id, user.token_version, "refresh"),
        expires_in=get_settings().access_token_minutes * 60,
    )


def _limit(request: Request) -> None:
    limiter.check(f"auth:{client_ip(request)}", get_settings().rate_limit_auth_per_min, 60)


@router.post("/register", response_model=TokenOut, status_code=status.HTTP_201_CREATED)
async def register(body: RegisterIn, request: Request, session: SessionDep) -> TokenOut:
    _limit(request)
    email = body.email.lower()
    if await session.scalar(select(func.count()).select_from(User).where(User.email == email)):
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists")
    user = User(email=email, password_hash=hash_password(body.password), display_name=body.display_name.strip(),
                timezone=body.timezone)
    session.add(user)
    try:
        await session.commit()
    except IntegrityError:  # concurrent registration with the same email
        await session.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists") from None
    return _tokens(user)


@router.post("/login", response_model=TokenOut)
async def login(body: LoginIn, request: Request, session: SessionDep) -> TokenOut:
    _limit(request)
    user = await session.scalar(select(User).where(User.email == body.email.lower()))
    if user is None:
        burn_verify()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect email or password")
    if not verify_password(body.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect email or password")
    return _tokens(user)


@router.post("/refresh", response_model=TokenOut)
async def refresh(body: RefreshIn, session: SessionDep) -> TokenOut:
    try:
        user_id, version = decode_token(body.refresh_token, "refresh")
    except TokenError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid refresh token") from None
    user = await session.get(User, user_id)
    if user is None or user.token_version != version:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid refresh token")
    return _tokens(user)


@router.post("/logout-all", status_code=status.HTTP_204_NO_CONTENT)
async def logout_all(user: CurrentUser, session: SessionDep) -> None:
    """Invalidates every access and refresh token issued to this account."""
    user.token_version += 1
    await session.commit()
