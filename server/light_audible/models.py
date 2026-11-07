from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    LargeBinary,
    Text,
    false,
    func,
    text,
    true,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Account(Base):
    __tablename__ = "account"
    __table_args__ = (CheckConstraint("id = 1", name="account_singleton"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    encrypted_auth: Mapped[bytes | None] = mapped_column(LargeBinary)
    locale: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(Text, server_default="signed_out", default="signed_out")
    position_sync_enabled: Mapped[bool] = mapped_column(Boolean, server_default=true(), default=True)
    consecutive_sync_failures: Mapped[int] = mapped_column(Integer, server_default="0", default=0)
    library_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    pending_login: Mapped[dict | None] = mapped_column(JSONB)


class Book(Base):
    __tablename__ = "books"

    asin: Mapped[str] = mapped_column(Text, primary_key=True)
    title: Mapped[str] = mapped_column(Text)
    authors: Mapped[str] = mapped_column(Text)
    narrators: Mapped[str] = mapped_column(Text)
    runtime_ms: Mapped[int] = mapped_column(BigInteger)
    purchase_date: Mapped[date | None] = mapped_column(Date)
    playable: Mapped[bool] = mapped_column(Boolean, server_default=true(), default=True)
    unplayable_reason: Mapped[str | None] = mapped_column(Text)
    acr: Mapped[str | None] = mapped_column(Text)
    chapters_fetched: Mapped[bool] = mapped_column(Boolean, server_default=false(), default=False)


class Chapter(Base):
    __tablename__ = "chapters"

    asin: Mapped[str] = mapped_column(Text, ForeignKey("books.asin", ondelete="CASCADE"), primary_key=True)
    idx: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(Text)
    start_ms: Mapped[int] = mapped_column(BigInteger)
    length_ms: Mapped[int] = mapped_column(BigInteger)


class Conversion(Base):
    """Queue convention: status='preparing' with started_at NULL = queued, NOT NULL = running."""

    __tablename__ = "conversions"

    asin: Mapped[str] = mapped_column(Text, ForeignKey("books.asin", ondelete="CASCADE"), primary_key=True)
    status: Mapped[str] = mapped_column(Text)
    attempts: Mapped[int] = mapped_column(Integer, server_default="0", default=0)
    error: Mapped[str | None] = mapped_column(Text)
    object_key: Mapped[str | None] = mapped_column(Text)
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_played_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Position(Base):
    __tablename__ = "positions"

    asin: Mapped[str] = mapped_column(Text, primary_key=True)
    position_ms: Mapped[int] = mapped_column(BigInteger)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    token_hash: Mapped[str] = mapped_column(Text, unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class PairingCode(Base):
    __tablename__ = "pairing_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    code_hash: Mapped[str] = mapped_column(Text)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    failed_attempts: Mapped[int] = mapped_column(Integer, server_default="0", default=0)
