from fastapi import HTTPException
from fastapi.encoders import jsonable_encoder


def fail(status, code, message, field=None, **data):
    detail = {"code": code, "message": message, **data}
    if field:
        detail["field"] = field
    raise HTTPException(status, jsonable_encoder(detail))


def invalid(field, message):
    fail(422, "validation", message, field=field)
