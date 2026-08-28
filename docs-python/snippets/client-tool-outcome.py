def tool_outcome(call_id: str, *, value=None, error=None) -> dict:
    return {"call_id": call_id, "ok": error is None, "value": value, "error": error}
