"""initial schema

Revision ID: 0001
Revises:
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "account",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("encrypted_auth", sa.LargeBinary),
        sa.Column("locale", sa.Text, nullable=False),
        sa.Column("status", sa.Text, nullable=False, server_default="signed_out"),
        sa.Column("position_sync_enabled", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("consecutive_sync_failures", sa.Integer, nullable=False, server_default="0"),
        sa.Column("library_synced_at", sa.DateTime(timezone=True)),
        sa.Column("pending_login", postgresql.JSONB),
        sa.CheckConstraint("id = 1", name="account_singleton"),
    )
    op.create_table(
        "books",
        sa.Column("asin", sa.Text, primary_key=True),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("authors", sa.Text, nullable=False),
        sa.Column("narrators", sa.Text, nullable=False),
        sa.Column("runtime_ms", sa.BigInteger, nullable=False),
        sa.Column("purchase_date", sa.Date),
        sa.Column("playable", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("unplayable_reason", sa.Text),
        sa.Column("acr", sa.Text),
        sa.Column("chapters_fetched", sa.Boolean, nullable=False, server_default=sa.false()),
    )
    op.create_table(
        "chapters",
        sa.Column("asin", sa.Text, sa.ForeignKey("books.asin", ondelete="CASCADE"), primary_key=True),
        sa.Column("idx", sa.Integer, primary_key=True),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("start_ms", sa.BigInteger, nullable=False),
        sa.Column("length_ms", sa.BigInteger, nullable=False),
    )
    op.create_table(
        "conversions",
        sa.Column("asin", sa.Text, sa.ForeignKey("books.asin", ondelete="CASCADE"), primary_key=True),
        sa.Column("status", sa.Text, nullable=False),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("error", sa.Text),
        sa.Column("object_key", sa.Text),
        sa.Column("size_bytes", sa.BigInteger),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("finished_at", sa.DateTime(timezone=True)),
        sa.Column("last_played_at", sa.DateTime(timezone=True)),
    )
    op.create_table(
        "positions",
        sa.Column("asin", sa.Text, primary_key=True),
        sa.Column("position_ms", sa.BigInteger, nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("token_hash", sa.Text, nullable=False, unique=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
    )
    op.create_table(
        "pairing_codes",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("code_hash", sa.Text, nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("failed_attempts", sa.Integer, nullable=False, server_default="0"),
    )


def downgrade() -> None:
    for table in ("pairing_codes", "devices", "positions", "conversions", "chapters", "books", "account"):
        op.drop_table(table)
