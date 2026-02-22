#!/usr/bin/env python3
"""
MCP server for pneumatic spring (пневморессора) artifact data analysis.

Reads measurement protocol CSV files from the artifacts/ directory and provides
statistical analysis tools for the Telegram bot's local LLM.

Artifacts structure:
  artifacts/
    180D1/    ← model folder
      Протокол замеров 180D1 10_02_26.csv
      ...
    160D1/
      ...

CSV format (each file has 10 measurements):
  Row 0: protocol title
  Row 1: date;...;model;...
  Row 2: №;h, мм;D, мм;hсж, мм;Dсж, мм;h 8 бар, мм;D 8 бар, мм
  Rows 3+: 1;173;177;77;187;237;157
           2;171;177;77;184;237;156
           ...
"""

import asyncio
import json
import re
import sys
from io import StringIO
from pathlib import Path
from typing import Any

import pandas as pd
import mcp.types as types
from mcp.server import Server
from mcp.server.stdio import stdio_server

# Artifacts directory: one level up from this script
ARTIFACTS_DIR = Path(__file__).parent.parent / "artifacts"

# Internal column names (positional, after the row-number column)
COLUMN_NAMES = ["h", "D", "h_compressed", "D_compressed", "h_8bar", "D_8bar"]

# Human-readable column descriptions for tool documentation
COLUMN_DESCRIPTIONS = {
    "h": "height at no pressure (мм)",
    "D": "diameter at no pressure (мм)",
    "h_compressed": "height when mechanically compressed (hсж, мм)",
    "D_compressed": "diameter when mechanically compressed (Dсж, мм)",
    "h_8bar": "height at 8 bar pressure (h 8 бар, мм)",
    "D_8bar": "diameter at 8 bar pressure (D 8 бар, мм)",
}

server = Server("artifacts-analyzer")


def parse_csv_file(filepath: Path) -> pd.DataFrame | None:
    """
    Parse a measurement protocol CSV file.

    Handles multiple encodings and different header formats.
    Returns a DataFrame with columns [h, D, h_compressed, D_compressed, h_8bar, D_8bar].
    """
    for encoding in ["utf-8", "cp1251", "latin-1"]:
        try:
            with open(filepath, encoding=encoding) as f:
                lines = f.readlines()

            # Find the first data row: row where first field is "1"
            data_start = None
            for i, line in enumerate(lines):
                fields = line.strip().split(";")
                if fields and fields[0].strip() == "1":
                    data_start = i
                    break

            if data_start is None:
                continue

            # Read exactly 10 measurement rows (some files have fewer)
            data_lines = lines[data_start : data_start + 10]
            if not data_lines:
                continue

            data_str = "".join(data_lines)
            df = pd.read_csv(StringIO(data_str), sep=";", header=None)

            # Column 0 is the measurement number (1-10), columns 1-6 are the values
            if df.shape[1] < 7:
                continue

            df = df.iloc[:, 1:7].copy()
            df.columns = COLUMN_NAMES
            df = df.apply(pd.to_numeric, errors="coerce")
            df = df.dropna(how="all")

            return df

        except Exception:
            continue

    return None


def extract_date_from_filename(filename: str) -> str:
    """Extract date string from filename like '180D1 19_11_25' → '19.11.2025'."""
    match = re.search(r"(\d{1,2})_(\d{1,2})_(\d{2,4})$", filename)
    if match:
        day, month, year = match.group(1), match.group(2), match.group(3)
        if len(year) == 2:
            year = "20" + year
        return f"{day.zfill(2)}.{month.zfill(2)}.{year}"
    return filename


def get_model_dir(model_name: str) -> Path:
    """Get and validate model directory path."""
    # Allow both "180D1" and "180d1" (case-insensitive lookup)
    model_dir = ARTIFACTS_DIR / model_name
    if not model_dir.exists():
        # Try case-insensitive search
        for d in ARTIFACTS_DIR.iterdir():
            if d.is_dir() and d.name.lower() == model_name.lower():
                return d
        raise ValueError(
            f"Model '{model_name}' not found. "
            f"Available: {[d.name for d in ARTIFACTS_DIR.iterdir() if d.is_dir()]}"
        )
    return model_dir


def load_all_model_data(model_name: str) -> tuple[pd.DataFrame, list[str]]:
    """Load and combine all CSV files for a model."""
    model_dir = get_model_dir(model_name)
    csv_files = sorted(model_dir.glob("*.csv"))

    if not csv_files:
        raise ValueError(f"No CSV files found for model '{model_name}'")

    all_dfs = []
    dates = []

    for csv_file in csv_files:
        df = parse_csv_file(csv_file)
        if df is not None and not df.empty:
            all_dfs.append(df)
            dates.append(extract_date_from_filename(csv_file.stem))

    if not all_dfs:
        raise ValueError(f"Could not parse any CSV files for model '{model_name}'")

    combined = pd.concat(all_dfs, ignore_index=True)
    return combined, dates


# ─── Tool definitions ──────────────────────────────────────────────────────────

@server.list_tools()
async def list_tools() -> list[types.Tool]:
    return [
        types.Tool(
            name="list_artifact_models",
            description=(
                "List all available pneumatic spring model names in the artifacts directory. "
                "Call this first to see what models can be analyzed."
            ),
            inputSchema={"type": "object", "properties": {}, "required": []},
        ),
        types.Tool(
            name="list_model_dates",
            description=(
                "List all measurement dates available for a specific model. "
                "Shows how many measurement sessions were recorded."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "model": {
                        "type": "string",
                        "description": "Model name, e.g. '180D1', '160D1', '140_2'",
                    }
                },
                "required": ["model"],
            },
        ),
        types.Tool(
            name="calculate_column_stats",
            description=(
                "Calculate statistics (mean, min, max, std, count) for a specific measurement "
                "column across ALL measurement dates for a given model. "
                "Use this to answer questions like 'what is the mean 8 bar height for 180D1?'. "
                f"Available columns: {', '.join(COLUMN_NAMES)}. "
                "Column meanings: "
                + "; ".join(f"{k}={v}" for k, v in COLUMN_DESCRIPTIONS.items())
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "model": {
                        "type": "string",
                        "description": "Model name, e.g. '180D1', '160D1', '140_2'",
                    },
                    "column": {
                        "type": "string",
                        "description": (
                            "Column to analyze. One of: "
                            + ", ".join(COLUMN_NAMES)
                            + ". Use 'h_8bar' for '8 bar height', 'D_8bar' for '8 bar diameter'."
                        ),
                        "enum": COLUMN_NAMES,
                    },
                },
                "required": ["model", "column"],
            },
        ),
        types.Tool(
            name="get_measurements_by_date",
            description=(
                "Get raw measurements for a specific model and date. "
                "Returns all 10 sample measurements from that session. "
                "Use list_model_dates first to find valid dates."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "model": {
                        "type": "string",
                        "description": "Model name, e.g. '180D1'",
                    },
                    "date": {
                        "type": "string",
                        "description": (
                            "Date string matching part of the filename, "
                            "e.g. '19_11_25', '10_02_26'. "
                            "Use list_model_dates to see available dates."
                        ),
                    },
                },
                "required": ["model", "date"],
            },
        ),
    ]


# ─── Tool handlers ─────────────────────────────────────────────────────────────

@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent]:
    try:
        if name == "list_artifact_models":
            return await _list_artifact_models()
        elif name == "list_model_dates":
            return await _list_model_dates(arguments["model"])
        elif name == "calculate_column_stats":
            return await _calculate_column_stats(arguments["model"], arguments["column"])
        elif name == "get_measurements_by_date":
            return await _get_measurements_by_date(
                arguments["model"], arguments.get("date", "")
            )
        else:
            return [types.TextContent(type="text", text=f"Unknown tool: {name}")]
    except Exception as e:
        return [types.TextContent(type="text", text=f"Error: {e}")]


async def _list_artifact_models() -> list[types.TextContent]:
    if not ARTIFACTS_DIR.exists():
        return [types.TextContent(type="text", text=f"Artifacts directory not found: {ARTIFACTS_DIR}")]

    models = sorted(
        d.name for d in ARTIFACTS_DIR.iterdir()
        if d.is_dir() and not d.name.startswith(".")
    )
    result = {
        "models": models,
        "count": len(models),
        "artifacts_path": str(ARTIFACTS_DIR),
    }
    return [types.TextContent(type="text", text=json.dumps(result, ensure_ascii=False))]


async def _list_model_dates(model: str) -> list[types.TextContent]:
    model_dir = get_model_dir(model)
    csv_files = sorted(model_dir.glob("*.csv"))

    dates = []
    for f in csv_files:
        dates.append({
            "file": f.name,
            "date": extract_date_from_filename(f.stem),
        })

    result = {
        "model": model,
        "measurement_sessions": len(dates),
        "dates": dates,
    }
    return [types.TextContent(type="text", text=json.dumps(result, ensure_ascii=False))]


async def _calculate_column_stats(model: str, column: str) -> list[types.TextContent]:
    if column not in COLUMN_NAMES:
        return [types.TextContent(
            type="text",
            text=f"Unknown column '{column}'. Available: {', '.join(COLUMN_NAMES)}"
        )]

    combined, dates = load_all_model_data(model)
    series = combined[column].dropna()

    result = {
        "model": model,
        "column": column,
        "column_description": COLUMN_DESCRIPTIONS.get(column, column),
        "statistics": {
            "mean": round(float(series.mean()), 3),
            "median": round(float(series.median()), 3),
            "min": round(float(series.min()), 3),
            "max": round(float(series.max()), 3),
            "std": round(float(series.std()), 3),
            "count": int(series.count()),
        },
        "measurement_sessions": len(dates),
        "date_range": f"{dates[0]} – {dates[-1]}" if dates else "unknown",
    }
    return [types.TextContent(type="text", text=json.dumps(result, ensure_ascii=False))]


async def _get_measurements_by_date(model: str, date: str) -> list[types.TextContent]:
    model_dir = get_model_dir(model)
    csv_files = sorted(model_dir.glob("*.csv"))

    # Find file matching the date string
    matched_file = None
    for f in csv_files:
        if date.lower() in f.stem.lower() or date.replace(".", "_") in f.stem:
            matched_file = f
            break

    # If no match, try partial match
    if matched_file is None and date:
        date_digits = re.sub(r"\D", "", date)
        for f in csv_files:
            stem_digits = re.sub(r"\D", "", f.stem)
            if date_digits and date_digits in stem_digits:
                matched_file = f
                break

    if matched_file is None:
        available = [extract_date_from_filename(f.stem) for f in csv_files]
        return [types.TextContent(
            type="text",
            text=f"No file found for date '{date}' in model '{model}'. Available dates: {available}"
        )]

    df = parse_csv_file(matched_file)
    if df is None or df.empty:
        return [types.TextContent(type="text", text=f"Could not parse file: {matched_file.name}")]

    result = {
        "model": model,
        "date": extract_date_from_filename(matched_file.stem),
        "file": matched_file.name,
        "sample_count": len(df),
        "columns": COLUMN_NAMES,
        "measurements": df.values.tolist(),
        "column_means": {col: round(float(df[col].mean()), 2) for col in COLUMN_NAMES},
    }
    return [types.TextContent(type="text", text=json.dumps(result, ensure_ascii=False))]


# ─── Entry point ───────────────────────────────────────────────────────────────

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            server.create_initialization_options(),
        )


if __name__ == "__main__":
    asyncio.run(main())
