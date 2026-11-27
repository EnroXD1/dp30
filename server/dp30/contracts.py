from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field


Role = Literal["dispatcher", "worker", "manager", "admin"]
Status = Literal["NEW", "ASSIGNED", "ACCEPTED", "IN_WORK", "COMPLETED", "CLOSED", "CANCELLED"]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Login(Input):
    login: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=128)


class Versioned(Input):
    operation_id: UUID
    expected_version: int = Field(ge=0)


class CreateRequest(Versioned):
    address: str = Field(min_length=1, max_length=250)
    description: str = Field(min_length=1, max_length=2000)
    priority: Literal["low", "normal", "high"]
    due_at: AwareDatetime
    parent_id: int | None = Field(default=None, gt=0)


class Command(Versioned):
    action: Literal["assign", "accept", "start", "complete", "return", "close", "cancel"]
    brigade_id: int | None = Field(default=None, gt=0)
    result: str | None = Field(default=None, min_length=1, max_length=4000)
    reason: str | None = Field(default=None, min_length=1, max_length=2000)


class BrigadeInput(Versioned):
    name: str = Field(min_length=1, max_length=120)
    active: bool = True


class UserInput(Versioned):
    display_name: str = Field(min_length=1, max_length=120)
    role: Role
    brigade_id: int | None = Field(default=None, gt=0)
    active: bool = True


class CreateUser(UserInput):
    login: str = Field(pattern=r"^[a-zA-Z0-9_.-]{1,64}$")
    password: str = Field(min_length=8, max_length=128)


class ReferenceInput(Versioned):
    kind: str = Field(min_length=1, max_length=60)
    code: str = Field(pattern=r"^[a-zA-Z0-9_.-]{1,50}$")
    title: str = Field(min_length=1, max_length=120)
    active: bool = True


class UserView(BaseModel):
    id: int
    login: str
    display_name: str
    role: Role
    brigade_id: int | None
    active: bool
    version: int


class ProfileView(UserView):
    brigade_name: str | None


class LoginView(BaseModel):
    token: str
    expires_at: AwareDatetime
    user: UserView


class BrigadeView(BaseModel):
    id: int
    name: str
    active: bool
    version: int


class ReferenceView(BaseModel):
    id: int
    kind: str
    code: str
    title: str
    active: bool
    version: int


class CardView(BaseModel):
    id: int
    number: int
    request_uid: UUID
    parent_id: int | None
    address: str
    description: str
    priority: Literal["low", "normal", "high"]
    due_at: AwareDatetime
    status: Status
    version: int
    result: str | None
    created_at: AwareDatetime
    brigade_id: int | None
    brigade_name: str | None
    server_time: AwareDatetime


class PageView(BaseModel):
    items: list[CardView]
    server_time: AwareDatetime
    next_offset: int | None


class SummaryView(BaseModel):
    total: int
    overdue: int
    by_status: dict[str, int]
    by_brigade: dict[int, int]
    server_time: AwareDatetime


class RequestEventView(BaseModel):
    id: int
    request_id: int
    actor_id: int
    actor_name: str
    kind: str
    status: Status
    version: int
    comment: str | None
    operation_id: UUID
    occurred_at: AwareDatetime


class AdminAuditView(BaseModel):
    id: int
    actor_id: int
    entity: str
    entity_id: int
    kind: str
    operation_id: UUID
    occurred_at: AwareDatetime


class Done(BaseModel):
    ok: bool


class ErrorDetail(BaseModel):
    code: str
    message: str
    field: str | None = None
    current: dict | None = None


class ErrorView(BaseModel):
    detail: ErrorDetail
