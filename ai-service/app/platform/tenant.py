"""Who is acting and in which societies (same shape as the Java `Tenant` record)."""

from __future__ import annotations

import contextvars
import enum
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field


class ActorType(str, enum.Enum):
    USER = "USER"
    SERVICE = "SERVICE"
    SYSTEM = "SYSTEM"


class NoTenantError(Exception):
    """Raised when a society-scoped operation runs without an active society."""


@dataclass(frozen=True)
class Tenant:
    user_id: uuid.UUID | None
    actor_type: ActorType
    active_society_id: uuid.UUID | None
    readable_society_ids: tuple[uuid.UUID, ...] = ()
    roles: frozenset[str] = field(default_factory=frozenset)
    bearer_token: str | None = None

    def __post_init__(self) -> None:
        if self.active_society_id is not None and self.active_society_id not in self.readable_society_ids:
            raise ValueError("active society must be readable")

    @staticmethod
    def system(society_id: uuid.UUID) -> "Tenant":
        """System actor inside one society (Kafka consumers, scheduled jobs)."""
        return Tenant(None, ActorType.SYSTEM, society_id, (society_id,))

    @staticmethod
    def platform() -> "Tenant":
        """System actor with no society: RLS returns no tenant rows."""
        return Tenant(None, ActorType.SYSTEM, None, ())

    def require_active_society(self) -> uuid.UUID:
        if self.active_society_id is None:
            raise NoTenantError("No active society for this request")
        return self.active_society_id

    def has_role(self, role: str) -> bool:
        return role in self.roles


_current: contextvars.ContextVar[Tenant | None] = contextvars.ContextVar("sos_tenant", default=None)


def current() -> Tenant:
    return _current.get() or Tenant.platform()


def optional() -> Tenant | None:
    return _current.get()


@contextmanager
def run_as(tenant: Tenant):
    token = _current.set(tenant)
    try:
        yield tenant
    finally:
        _current.reset(token)
