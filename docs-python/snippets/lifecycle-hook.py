async def on_runtime_event(event: str, *, trace_id: str) -> None:
    print({"event": event, "trace_id": trace_id})
