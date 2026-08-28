from dataclasses import dataclass


@dataclass(frozen=True)
class RemoteAgentDefinition:
    """Semantic identity of the remote Agent; transport stays in runtime."""

    name: str = "versatile-remote-agent"
    description: str = "Remote Agent exposed through the Versatile adapter"
