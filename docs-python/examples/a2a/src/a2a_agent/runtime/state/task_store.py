# coding: utf-8
"""最小可写 TaskStore 替身：基于 SQLite 持久化序列化后的 A2A Task。

实现 `a2a.server.tasks.TaskStore` 的协议库基类（save/get/list/delete），把 Task
protobuf 序列化后按 id 存进 SQLite。**只做验收用最小可用替身**——不做生产 Redis
TaskStore 的并发、TTL、跨副本语义（compatibility.md：生产以 Redis 为状态外置前置）。

验证目标：`create_a2a_app(task_store=...)` 接受宿主注入的协议库 TaskStore，且 Task
生命周期（提交 → 查询 → 终态）可经它读写。
"""
from __future__ import annotations

import sqlite3
import threading
from typing import Optional

from a2a.server.tasks import TaskStore
from a2a.types import ListTasksRequest, ListTasksResponse, Task


class SqliteTaskStore(TaskStore):
    """把 Task 持久化到单文件 SQLite 的最小实现。"""

    def __init__(self, path: str = ":memory:") -> None:
        self._path = path
        self._lock = threading.Lock()
        # `:memory:` 下每次 connect 都是一个新的空库，故持有一个长连接；
        # 文件模式下每操作一个短连接。验收级够用。
        self._conn = self._connect()
        self._init()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init(self) -> None:
        with self._lock:
            self._conn.execute(
                "CREATE TABLE IF NOT EXISTS tasks ("
                "id TEXT PRIMARY KEY,"
                "context_id TEXT,"
                "state TEXT,"
                "payload BLOB NOT NULL)"
            )
            self._conn.commit()

    @staticmethod
    def _task_id(task: Task) -> str:
        return task.id  # type: ignore[attr-defined]

    async def save(self, task: Task, context) -> None:
        payload = task.SerializeToString()
        state = ""
        status = getattr(task, "status", None)
        if status is not None:
            state = str(getattr(status, "state", "") or "")
        with self._lock:
            self._conn.execute(
                "INSERT INTO tasks (id, context_id, state, payload) "
                "VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET "
                "context_id=excluded.context_id, state=excluded.state, payload=excluded.payload",
                (self._task_id(task), getattr(task, "context_id", ""), state, payload),
            )
            self._conn.commit()

    async def get(self, task_id: str, context) -> Optional[Task]:
        with self._lock:
            row = self._conn.execute(
                "SELECT payload FROM tasks WHERE id = ?", (task_id,)
            ).fetchone()
        if row is None:
            return None
        task = Task()
        task.ParseFromString(bytes(row["payload"]))
        return task

    async def list(self, params: ListTasksRequest, context) -> ListTasksResponse:
        with self._lock:
            rows = self._conn.execute(
                "SELECT payload FROM tasks ORDER BY rowid"
            ).fetchall()
        response = ListTasksResponse()
        for row in rows:
            task = Task()
            task.ParseFromString(bytes(row["payload"]))
            response.tasks.append(task)
        return response

    async def delete(self, task_id: str, context) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
            self._conn.commit()
