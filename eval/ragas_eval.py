#!/usr/bin/env python3
"""
Dragon Agent — Ragas RAG 评测脚本

用法:
    pip install ragas langchain-openai datasets pandas
    python ragas_eval.py --api-url http://localhost:8080 --cookie "SESSION=xxx" --eval-set eval_questions.json

输出指标:
    - context_precision: 检索到的上下文中有多少是相关的
    - context_recall: 相关的上下文有多少被检索到
    - faithfulness: 生成的答案是否忠实于上下文
    - answer_relevancy: 答案是否与问题相关
"""

import argparse
import json
import sys
import os
from typing import List, Dict

import requests
import pandas as pd
from datasets import Dataset


def fetch_context(api_url: str, cookie: str, questions: List[str]) -> List[Dict]:
    """从后端 eval-dataset 接口获取检索上下文"""
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie

    resp = requests.post(
        f"{api_url}/api/documents/eval-dataset",
        json={"questions": questions},
        headers=headers,
    )
    resp.raise_for_status()
    return resp.json()


def load_eval_set(path: str) -> List[Dict]:
    """加载评测集 JSON 文件"""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data


def run_ragas(eval_records: List[Dict]):
    """运行 Ragas 评测"""
    from ragas import evaluate
    from ragas.metrics import (
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
    )
    from langchain_openai import ChatOpenAI

    # 使用 DeepSeek 作为评判模型（OpenAI 兼容）
    api_key = os.getenv("AI_API_KEY", "")
    base_url = os.getenv("AI_BASE_URL", "https://api.deepseek.com")

    llm = ChatOpenAI(
        model="deepseek-chat",
        api_key=api_key,
        base_url=base_url,
        temperature=0,
    )

    dataset_dict = {
        "question": [],
        "answer": [],
        "contexts": [],
        "ground_truth": [],
    }
    for r in eval_records:
        dataset_dict["question"].append(r["question"])
        dataset_dict["answer"].append(r.get("answer", ""))
        dataset_dict["contexts"].append(r.get("contexts", []))
        dataset_dict["ground_truth"].append(r.get("ground_truth", ""))

    dataset = Dataset.from_dict(dataset_dict)

    result = evaluate(
        dataset=dataset,
        metrics=[context_precision, context_recall, faithfulness, answer_relevancy],
        llm=llm,
    )

    print("\n============= Ragas 评测结果 =============")
    df = result.to_pandas()
    print(df.to_string())
    print("\n--- 平均分 ---")
    for col in df.columns:
        avg = df[col].mean()
        print(f"  {col}: {avg:.4f}")

    return result


def main():
    parser = argparse.ArgumentParser(description="Dragon Agent Ragas 评测")
    parser.add_argument("--api-url", default="http://localhost:8080",
                        help="后端地址")
    parser.add_argument("--cookie", default="",
                        help="认证 Cookie (从浏览器 F12 复制)")
    parser.add_argument("--eval-set", default="eval_questions.json",
                        help="评测集 JSON 文件路径")
    parser.add_argument("--export-only", action="store_true",
                        help="仅导出检索上下文，不运行 Ragas")
    args = parser.parse_args()

    # 1. 加载评测集
    eval_data = load_eval_set(args.eval_set)
    questions = [item["question"] for item in eval_data]
    print(f"加载 {len(questions)} 个评测问题")

    # 2. 从后端获取检索上下文
    print("获取检索上下文...")
    contexts = fetch_context(args.api_url, args.cookie, questions)

    # 3. 组装数据
    eval_records = []
    for i, item in enumerate(eval_data):
        eval_records.append({
            "question": item["question"],
            "ground_truth": item.get("ground_truth", ""),
            "contexts": contexts[i].get("contexts", []) if i < len(contexts) else [],
            "answer": item.get("answer", ""),  # 如果有预生成的答案
        })

    # 4. 导出或评测
    export_path = "eval_export.json"
    with open(export_path, "w", encoding="utf-8") as f:
        json.dump(eval_records, f, ensure_ascii=False, indent=2)
    print(f"评测数据已导出: {export_path}")

    if args.export_only:
        print("--export-only 模式，跳过 Ragas 评测。")
        return

    # 5. 运行 Ragas
    print("\n运行 Ragas 评测...")
    run_ragas(eval_records)


if __name__ == "__main__":
    main()
