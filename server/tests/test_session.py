from fastapi import Depends
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible.db import get_session


async def test_get_session_executes_query(app, client, migrated_database):
    @app.get("/_test/select1")
    async def select1(session: AsyncSession = Depends(get_session)) -> dict[str, int]:
        return {"value": (await session.execute(text("SELECT 1"))).scalar_one()}

    response = await client.get("/_test/select1")
    assert response.status_code == 200
    assert response.json() == {"value": 1}
