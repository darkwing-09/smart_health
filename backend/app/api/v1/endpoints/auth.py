import hashlib
from datetime import datetime, timedelta, timezone
import uuid
from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials
from jose import jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_db, security_bearer, get_current_user, get_redis_pool
from app.core.config import settings
from app.core.rate_limit import check_rate_limit
from app.models.audit import AuditLog
from app.models.user import User
from app.schemas.timeline import LoginRequest, TokenResponse


router = APIRouter(prefix="/auth", tags=["authentication"])
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")


@router.post("/login", response_model=TokenResponse, status_code=status.HTTP_200_OK)
async def login(
    payload: LoginRequest,
    request: Request,
    db: AsyncSession = Depends(get_db)
) -> TokenResponse:
    # 1. Enforce sliding-window rate limit per IP
    await check_rate_limit(
        request=request,
        scope="auth:login",
        limit=settings.RATE_LIMIT_LOGIN_PER_MIN
    )

    client_ip = request.client.host if request.client else None
    stmt = select(User).where(User.email == payload.email)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()

    if not user or not pwd_context.verify(payload.password, user.hashed_password):
        if user:
            # Audit failed login attempt for existing user
            audit_entry = AuditLog(
                id=uuid.uuid4(),
                user_id=user.id,
                actor="client:ip",
                action="login_failed",
                target_ref=f"user:{user.id}",
                detail={"reason": "invalid_credentials", "email": payload.email},
                ip_address=client_ip
            )
            db.add(audit_entry)
            await db.commit()

        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"}
        )


    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    expire = datetime.now(timezone.utc) + access_token_expires
    token_jti = str(uuid.uuid4())
    to_encode = {"sub": str(user.id), "exp": expire, "jti": token_jti}
    encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm="HS256")

    # Mock rolling refresh token
    refresh_token = jwt.encode(
        {"sub": str(user.id), "exp": datetime.now(timezone.utc) + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )

    # Audit successful login
    audit_success = AuditLog(
        id=uuid.uuid4(),
        user_id=user.id,
        actor="user:auth",
        action="login_success",
        target_ref=f"user:{user.id}",
        detail={"method": "password_argon2", "jti": token_jti},
        ip_address=client_ip
    )
    db.add(audit_success)
    await db.commit()

    return TokenResponse(
        access_token=encoded_jwt,
        refresh_token=refresh_token,
        token_type="bearer",
        expires_in=int(access_token_expires.total_seconds()),
        user_id=user.id
    )


@router.post("/logout", status_code=status.HTTP_200_OK, summary="Revoke current session token immediately")
async def logout(
    request: Request,
    credentials: HTTPAuthorizationCredentials = Depends(security_bearer),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> dict[str, str]:
    """
    Revokes the current JWT bearer token immediately by registering its JTI in Redis.
    Guarantees instant session termination across distributed services.
    """
    token = credentials.credentials
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
        exp = payload.get("exp")
        jti = payload.get("jti")
        now_ts = datetime.now(timezone.utc).timestamp()
        ttl = int(exp - now_ts) if exp else settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60
        if ttl <= 0:
            ttl = 60
    except Exception:
        jti = None
        ttl = settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60

    redis_client = get_redis_pool()
    token_key = f"revoked_token:{jti}" if jti else f"revoked_token:{hashlib.sha256(token.encode()).hexdigest()}"
    await redis_client.set(token_key, "1", ex=ttl)

    # Audit logout
    client_ip = request.client.host if request.client else None
    audit_entry = AuditLog(
        id=uuid.uuid4(),
        user_id=current_user.id,
        actor="user:auth",
        action="logout_revoked",
        target_ref=f"user:{current_user.id}",
        detail={"jti": jti} if jti else {"revocation_type": "hash"},
        ip_address=client_ip
    )
    db.add(audit_entry)
    await db.commit()

    return {"status": "success", "message": "Token revoked successfully"}

