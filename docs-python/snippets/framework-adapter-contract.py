from collections.abc import Iterable


def to_runtime_chunks(agent_events: Iterable[dict]) -> Iterable[dict]:
    for event in agent_events:
        kind = event.get("kind", "text")
        yield {"event": kind, "text": event.get("text", "")}
