from typing import Any

from agent_runtime.adapters.inbound.rest.channel import RestChannel


class ExampleChannel:
    """边界示例；真实业务字段应在此解析，不进入 domain。"""

    def parse_request(self, body: dict[str, Any], *, conversation_id: str) -> Any:
        return {"body": body, "conversation_id": conversation_id}

    def build_context(self, parsed: Any) -> Any:
        return parsed

    def format_event(self, event: Any, *, stage: str) -> Any:
        return event

    def format_error(self, error: Any, *, stage: str) -> Any:
        return {"success": False, "error": str(error), "stage": stage}


assert isinstance(ExampleChannel(), RestChannel)
