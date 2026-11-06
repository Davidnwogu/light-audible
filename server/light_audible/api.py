from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import pairing
from light_audible.db import get_session
from light_audible.models import Device

router = APIRouter(prefix="/api")


class PairRequest(BaseModel):
    code: str


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/pair")
async def pair(
    body: PairRequest,
    db: AsyncSession = Depends(get_session),
    clock=Depends(pairing.get_clock),
):
    try:
        token = await pairing.redeem(db, body.code, now=clock())
    except pairing.PairingError:
        return JSONResponse({"error": "invalid_code"}, status_code=400)
    return {"token": token}


@router.get("/library")
async def library(_device: Device = Depends(pairing.require_device)) -> list:
    # Temporary stub; replaced by the real library listing.
    return []
