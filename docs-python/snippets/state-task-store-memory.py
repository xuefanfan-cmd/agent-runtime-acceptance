class MemoryTaskStore:
    def __init__(self):
        self._items = {}

    async def put(self, task_id: str, value: dict) -> None:
        self._items[task_id] = value

    async def get(self, task_id: str):
        return self._items.get(task_id)
