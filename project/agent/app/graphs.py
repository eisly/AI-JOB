from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph
from pydantic import ValidationError

from .errors import HandlerError
from .handlers import ExecutionContext, get_handler
from .operations import (
    GROWTH_ANALYZE,
    INTERVIEW_REPORT,
    INTERVIEW_TURN,
    JD_ANALYZE,
    JOB_MATCH,
    JOB_SEARCH,
    LEARNING_EVALUATE,
    LEARNING_PLAN,
    LEARNING_QUESTION,
    PROJECT_PLAN,
    PROJECT_RECOMMEND,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    RESUME_PARSE,
)
from .skills import get_skill

GRAPH_VERSION = "career-orchestrator-v1"
DOMAIN_OPERATIONS: dict[str, tuple[str, ...]] = {
    "profile": (RESUME_PARSE, RESUME_DIAGNOSE, RESUME_OPTIMIZE),
    "job": (JD_ANALYZE, JOB_MATCH, JOB_SEARCH),
    "learning": (
        LEARNING_PLAN,
        LEARNING_QUESTION,
        LEARNING_EVALUATE,
        GROWTH_ANALYZE,
    ),
    "interview": (INTERVIEW_TURN, INTERVIEW_REPORT),
    "project": (PROJECT_RECOMMEND, PROJECT_PLAN),
}
OPERATION_DOMAINS = {
    operation: domain
    for domain, operations in DOMAIN_OPERATIONS.items()
    for operation in operations
}


class CareerState(TypedDict, total=False):
    operation: str
    context: ExecutionContext
    result: dict[str, Any]
    meta: dict[str, Any]


def get_operation_domain(operation: str) -> str:
    domain = OPERATION_DOMAINS.get(operation)
    if domain is None:
        raise HandlerError(
            "UNSUPPORTED_OPERATION",
            f"不支持的 operation: {operation}",
            retryable=False,
        )
    return domain


def _operation_node(operation: str) -> Callable[[CareerState], Awaitable[CareerState]]:
    async def execute(state: CareerState) -> CareerState:
        handler = get_handler(operation)
        result, meta = await handler.execute(state["context"])
        return {"result": result, "meta": meta}

    return execute


async def _validate_result(state: CareerState) -> CareerState:
    operation = state["operation"]
    try:
        result = get_skill(operation).validate_result(state["result"])
    except (KeyError, ValueError, ValidationError) as exc:
        raise HandlerError(
            "RESULT_CONTRACT_INVALID",
            f"{operation} 的结果未通过最终契约校验",
            retryable=False,
        ) from exc
    meta = dict(state["meta"])
    meta.update(
        {
            "graph": GRAPH_VERSION,
            "subgraph": get_operation_domain(operation),
            "resultValidated": True,
        }
    )
    return {"result": result, "meta": meta}


def _build_domain_graph(domain: str) -> Any:
    operations = DOMAIN_OPERATIONS[domain]
    routes = {operation.lower(): operation.lower() for operation in operations}
    builder = StateGraph(CareerState)
    for operation in operations:
        node_name = operation.lower()
        builder.add_node(node_name, _operation_node(operation))
        builder.add_edge(node_name, "validate_result")
    builder.add_node("validate_result", _validate_result)
    builder.add_conditional_edges(START, lambda state: state["operation"].lower(), routes)
    builder.add_edge("validate_result", END)
    return builder.compile()


class CareerOrchestrator:
    def __init__(self) -> None:
        builder = StateGraph(CareerState)
        for domain in DOMAIN_OPERATIONS:
            builder.add_node(domain, _build_domain_graph(domain))
            builder.add_edge(domain, END)
        builder.add_conditional_edges(
            START,
            lambda state: get_operation_domain(state["operation"]),
            {domain: domain for domain in DOMAIN_OPERATIONS},
        )
        self.graph = builder.compile()

    async def execute(self, context: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        operation = context.run.operation
        get_operation_domain(operation)
        final = await self.graph.ainvoke(
            {
                "operation": operation,
                "context": context,
            }
        )
        return final["result"], final["meta"]