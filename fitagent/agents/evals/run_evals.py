"""Run the FitAgent eval suite.

    uv run python -m evals.run_evals            # LangSmith experiment (needs LANGSMITH_API_KEY)
    uv run python -m evals.run_evals --offline  # local run, prints a scorecard, exits 1 on failure

The model under test is whatever FITAGENT_MODEL points to; without an LLM key the deterministic
fallbacks are evaluated (useful as a baseline and in CI).
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from collections import defaultdict

from fitagent_agents import (
    build_coach_graph,
    build_planner_graph,
    configure_tracing,
    get_chat_model,
    load_env,
    run_coach,
    run_planner,
)

from .evaluators import COACH_EVALUATORS, PLANNER_EVALUATORS
from .scenarios import COACH_EXAMPLES, PLANNER_EXAMPLES, provider

COACH_DATASET = "fitagent-coach"
PLANNER_DATASET = "fitagent-planner"


def _targets():
    llm = get_chat_model()
    coach, planner = build_coach_graph(provider(), llm), build_planner_graph(provider(), llm)

    async def coach_target(inputs: dict) -> dict:
        r = await run_coach(coach, inputs["user"], [], inputs["message"])
        return {"text": r.text, "safety": r.safety, "tools_used": r.tools_used, "used_llm": r.used_llm}

    async def planner_target(inputs: dict) -> dict:
        r = await run_planner(planner, inputs["user"])
        return {"plan": r.plan.model_dump(), "source": r.source, "attempts": r.attempts}

    return coach_target, planner_target


async def run_offline() -> int:
    coach_target, planner_target = _targets()
    totals: dict[str, list[bool]] = defaultdict(list)
    failures = []
    for examples, target, evaluators in ((COACH_EXAMPLES, coach_target, COACH_EVALUATORS),
                                         (PLANNER_EXAMPLES, planner_target, PLANNER_EVALUATORS)):
        for ex in examples:
            out = await target(ex["inputs"])
            for ev in evaluators:
                res = ev(ex["inputs"], out, ex["outputs"])
                totals[res["key"]].append(bool(res["score"]))
                if not res["score"]:
                    failures.append(f"{res['key']}: {ex['inputs']} {res.get('comment', '')}")
    print(f"{'evaluator':<20} pass")
    for key, results in totals.items():
        print(f"{key:<20} {sum(results)}/{len(results)}")
    for f in failures:
        print("FAIL", f)
    return 1 if failures else 0


def _ensure_dataset(client, name: str, examples: list[dict]) -> None:
    if client.has_dataset(dataset_name=name):
        return
    ds = client.create_dataset(name, description=f"FitAgent {name} scenarios")
    client.create_examples(dataset_id=ds.id, examples=examples)


async def run_langsmith() -> int:
    from langsmith import Client, aevaluate

    if not configure_tracing():
        print("LANGSMITH_API_KEY is not set — use --offline or export the key.", file=sys.stderr)
        return 2
    client = Client()
    _ensure_dataset(client, COACH_DATASET, COACH_EXAMPLES)
    _ensure_dataset(client, PLANNER_DATASET, PLANNER_EXAMPLES)
    coach_target, planner_target = _targets()
    await aevaluate(coach_target, data=COACH_DATASET, evaluators=COACH_EVALUATORS, experiment_prefix="coach")
    await aevaluate(planner_target, data=PLANNER_DATASET, evaluators=PLANNER_EVALUATORS, experiment_prefix="planner")
    print("Experiments uploaded to LangSmith.")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true", help="evaluate locally without LangSmith")
    args = parser.parse_args()
    load_env()  # API keys from fitagent/.env
    sys.exit(asyncio.run(run_offline() if args.offline else run_langsmith()))


if __name__ == "__main__":
    main()
