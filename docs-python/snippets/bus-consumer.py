seen_event_ids: set[str] = set()


def accept_once(event: dict) -> bool:
    event_id = event["id"]
    if event_id in seen_event_ids:
        return False
    seen_event_ids.add(event_id)
    return True
