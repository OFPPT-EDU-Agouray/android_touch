"""MCP server that exposes the android_touch SDK as LLM-friendly tools.

Designed to be launched as a standard MCP stdio server:

    android-touch-mcp

Or imported and embedded:

    from android_touch_mcp.server import build_server
    server = build_server(base_url="http://localhost:9889")
    server.run()  # blocks on stdio
"""
from __future__ import annotations

from .server import build_server, main

__all__ = ["build_server", "main"]
__version__ = "0.4.0"
