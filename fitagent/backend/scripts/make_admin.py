"""Grant (or revoke) campus-admin rights: uv run python scripts/make_admin.py admin@college.edu "IIT Example" [--revoke]

Admins only ever see aggregates for their own college (see app/routers/admin.py)."""

from __future__ import annotations

import asyncio
import sys

from sqlalchemy import select

sys.path.insert(0, ".")
from app.db import SessionLocal
from app.models import User


async def main(email: str, college: str | None, revoke: bool) -> int:
    async with SessionLocal() as session:
        user = await session.scalar(select(User).where(User.email == email.lower()))
        if user is None:
            print(f"No user {email}", file=sys.stderr)
            return 1
        user.role = "student" if revoke else "admin"
        if college:
            user.college = college
        await session.commit()
        print(f"{email}: role={user.role}, college={user.college}")
        return 0


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        sys.exit(2)
    sys.exit(asyncio.run(main(args[0], args[1] if len(args) > 1 else None, "--revoke" in sys.argv)))
