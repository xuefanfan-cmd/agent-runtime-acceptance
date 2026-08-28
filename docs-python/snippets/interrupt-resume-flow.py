from agent_runtime.domain.context import ServeRequest


def build_resume_request(original: ServeRequest, interaction_id: str, answer: str) -> ServeRequest:
    """续接仍走 ServeRequest / stream_query，不新增 Handler API。"""
    return ServeRequest.for_resume(
        original,
        interaction_id=interaction_id,
        user_input=answer,
    )
