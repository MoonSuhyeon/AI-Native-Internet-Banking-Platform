from datetime import date, datetime
from decimal import Decimal
import enum
from uuid import uuid4

from sqlalchemy.inspection import inspect


def new_number(prefix: str) -> str:
    return f"{prefix}-{datetime.utcnow():%Y%m%d%H%M%S}-{uuid4().hex[:8].upper()}"


def today_text() -> str:
    return datetime.utcnow().strftime("%Y%m%d")


def serialize(value):
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, enum.Enum):
        return value.value
    return value


def model_to_dict(obj) -> dict:
    mapper = inspect(obj.__class__)
    return {column.key: serialize(getattr(obj, column.key)) for column in mapper.columns}


def clean_payload(model, payload: dict, *, partial: bool = False) -> dict:
    cols = inspect(model).columns
    allowed = {col.key for col in cols if not col.primary_key or col.key in payload}
    if partial:
        return {key: value for key, value in payload.items() if key in allowed}
    return {key: value for key, value in payload.items() if key in allowed and value is not None}
