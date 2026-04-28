"""Shared type aliases used by the MCP tool signatures.

Kept here so the server.py module stays focused on wiring and the
Literal/enum types show up cleanly in the generated JSON schemas that
FastMCP advertises to clients.
"""
from __future__ import annotations

from typing import Literal

BY_LITERAL = Literal["text", "viewId", "contentDesc", "class"]
"""Accepted values for the ``by`` parameter on every matcher tool."""


__all__ = ["BY_LITERAL"]
