#!/usr/bin/env python3
"""Sovereign Horizon: responsive Project Intermix cockpit."""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from rich.markup import escape
from rich.style import Style
from textual import events
from textual.app import App, ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.message import Message
from textual.widgets import (
    Button,
    Collapsible,
    DirectoryTree,
    Label,
    Markdown,
    Static,
    TextArea,
)
from textual.widgets.text_area import TextAreaTheme

from llm_controller import (
    cancel_active_operations,
    get_active_task_status,
    get_agent_runtime_status,
    get_engine_status,
    get_store,
    local_command,
    recent_transcript,
    record_manual_workspace_event,
    shutdown_inference,
    stream_inference,
)
from audio_bridge import (
    AUDIO_ARCHIVE_DIR,
    audio_archive_details,
    bridge_status,
    cancel_request_playback,
    claim_audio_ready,
    next_audio_ready_request,
    play_claimed_request,
    request_result,
    submit_completed_response,
)
from idle_maintenance import (
    cancel_idle_maintenance,
    maintenance_status,
    run_idle_maintenance,
)
from idle_reporter import generate_idle_report
from prompt_builder import ENGINE_CONTEXT_TOKENS, INPUT_LIMIT_TOKENS
from runtime_config import CONFIG, PUBLIC_RELEASE
from workspace_documenter import update_project_documentation
from workspace_state import (
    MAX_EDITOR_BYTES,
    META_DIR,
    WORKSPACE_DIR,
    read_text,
    relative_path,
    safe_path,
    write_text,
)


SLASH_COMMANDS = [
    "/web ",
    "/web status",
    "/web plan ",
    "/web last plan",
    "/web auto status",
    "/web watchlist",
    "/web watch remove ",
    "/providers status",
    "/providers setup",
    "/facts status",
    "/facts style toggle",
    "/facts style hybrid",
    "/facts style core",
    "/workspace ",
    "/files",
    "/agent status",
    "/cancel",
    "/generation status",
    "/brief ",
    "/deep ",
    "/create ",
    "/response status",
    "/response mode auto",
    "/task status",
    "/tasks",
    "/task new ",
    "/task resume ",
    "/task pause",
    "/approvals",
    "/approve ",
    "/deny ",
    "/research status",
    "/research findings",
    "/research scan",
    "/docs refresh",
    "/maintenance status",
    "/report now",
    "/report status",
    "/report sensitive off",
    "/status",
    "/engine status",
    "/engine unload",
    "/engine mode auto",
    "/engine mode resident",
    "/engine mode pty",
    "/model status",
    "/model mode auto",
    "/model mode librarian",
    "/model mode reasoning",
    "/persona status",
    "/persona history",
    "/persona undo",
    "/persona auto on",
    "/persona auto off",
    "/sanctuary status",
    "/sanctuary on",
    "/sanctuary off",
    "/inspect context",
    "/memory facts",
    "/memory domains",
    "/memory timeline ",
    "/memory audit",
    "/memory sensitive status",
    "/memory sensitive on",
    "/memory sensitive off",
    "/memory sensitive retention ",
    "/memory event forget ",
    "/memory event purge ",
    "/memory search ",
    "/memory pin ",
    "/memory forget ",
    "/sessions",
    "/session ",
    "/new",
    "/export",
    "/clean",
]

WELCOME = (
    f"[bold #39d5ff]{escape(CONFIG.assistant_name.upper())} / COGNITION[/]  "
    f"[bold #c39aff]// {PUBLIC_RELEASE}[/]\n"
    "[#8492a8]Trust + Speed · verified facts · adaptive resident GPU · bounded 8K[/]"
)

PHASE_STYLES = {
    "ready": ("READY", "#67e8c2"),
    "recalling": ("RECALLING", "#a970ff"),
    "searching": ("SEARCHING", "#ffca6b"),
    "assembling": ("ASSEMBLING", "#a970ff"),
    "warming": ("WARMING", "#ffca6b"),
    "engine_ready": ("ENGINE HOT", "#39d5ff"),
    "engine_hot": ("ENGINE HOT", "#39d5ff"),
    "model_switch": ("MODEL SWITCH", "#c39aff"),
    "generating": ("THINKING", "#39d5ff"),
    "streaming": ("STREAMING", "#67e8c2"),
    "verifying": ("VERIFYING", "#a970ff"),
    "fallback": ("FALLBACK", "#ffca6b"),
    "stopping": ("STOPPING", "#ff6f91"),
}

IDLE_SECONDS = 60
MAINTENANCE_INTERVAL_SECONDS = 300
STREAM_REPAINT_SECONDS = 0.09
COMPOSER_COLLAPSED_HEIGHT = 3
COMPOSER_MAX_VISIBLE_LINES = 7
COMPOSER_BORDER_HEIGHT = 1

_BASE_SYNTAX = TextAreaTheme.get_builtin_theme("css")
INTERMIX_EDITOR_THEME = TextAreaTheme(
    name="intermix_horizon",
    base_style=Style(color="#e7edf5", bgcolor="#070d17"),
    gutter_style=Style(color="#66758b", bgcolor="#070d17"),
    cursor_style=Style(color="#060a12", bgcolor="#39d5ff"),
    cursor_line_style=Style(bgcolor="#0a1422"),
    cursor_line_gutter_style=Style(
        color="#c39aff", bgcolor="#0a1422", bold=True
    ),
    bracket_matching_style=Style(color="#67e8c2", bold=True, underline=True),
    selection_style=Style(bgcolor="#3b2464"),
    syntax_styles=dict(_BASE_SYNTAX.syntax_styles),
)


class MessageComposer(TextArea):
    """Soft-wrapped chat composer with explicit send and newline semantics."""

    class Submitted(Message):
        """Posted when Enter submits the complete composer document."""

        def __init__(self, composer: "MessageComposer", value: str) -> None:
            super().__init__()
            self.composer = composer
            self.value = value

        @property
        def control(self) -> "MessageComposer":
            return self.composer

    def __init__(self, *, placeholder: str, id: str) -> None:
        super().__init__(
            "",
            id=id,
            placeholder=placeholder,
            soft_wrap=True,
            show_line_numbers=False,
            compact=True,
            highlight_cursor_line=False,
            max_checkpoints=20,
        )

    async def _on_key(self, event: events.Key) -> None:
        if self.disabled or self.read_only:
            return
        if event.key == "enter":
            event.stop()
            event.prevent_default()
            self.post_message(self.Submitted(self, self.text))
            return
        if event.key in {"shift+enter", "ctrl+enter"}:
            event.stop()
            event.prevent_default()
            result = self.replace(
                "\n",
                *self.selection,
                maintain_selection_offset=False,
            )
            self.move_cursor(result.end_location)
            return
        await super()._on_key(event)

    def update_suggestion(self) -> None:
        """Retain lightweight slash completion without the single-line Input."""

        value = self.text
        if (
            not value.startswith("/")
            or "\n" in value
            or not self.cursor_at_end_of_text
        ):
            self.suggestion = ""
            return
        lowered = value.casefold()
        for candidate in SLASH_COMMANDS:
            if candidate.casefold().startswith(lowered) and len(candidate) > len(value):
                self.suggestion = candidate[len(value):]
                return
        self.suggestion = ""


def _short_time(timestamp: str | None = None) -> str:
    if not timestamp:
        return datetime.now().strftime("%H:%M")
    value = str(timestamp)
    if "T" in value:
        value = value.split("T", 1)[1]
    return value[:5] if len(value) >= 5 else value


class WorkspaceTree(DirectoryTree):
    """Lazy workspace tree that keeps Intermix's internal ledger out of view."""

    # Emoji glyph widths vary across Android terminal fonts. These deliberately
    # plain markers preserve alignment in Termux desktop and compact modes.
    ICON_FILE = "· "
    ICON_NODE = "▸ "
    ICON_NODE_EXPANDED = "▾ "

    def filter_paths(self, paths):
        return [
            path
            for path in paths
            if path != META_DIR
            and META_DIR not in path.parents
            and path.name != "__pycache__"
        ]


class VoiceArchiveTree(DirectoryTree):
    """Read-only view of the managed Android voice archive."""

    ICON_FILE = "· "
    ICON_NODE = "▸ "
    ICON_NODE_EXPANDED = "▾ "

    def filter_paths(self, paths):
        return [path for path in paths if path.is_dir() or path.suffix.lower() == ".wav"]


class MessageBlock(Static):
    COLORS = {
        "user": "#c39aff",
        "assistant": "#39d5ff",
        "system": "#ffca6b",
    }

    def __init__(
        self,
        speaker: str,
        content: str,
        *,
        kind: str,
        timestamp: str | None = None,
        pending: bool = False,
    ):
        super().__init__(classes=f"message {kind}-message")
        self.speaker = speaker
        self.content_text = content
        self.kind = kind
        self.timestamp = _short_time(timestamp)
        self.pending = pending

    def set_content(self, content: str, pending: bool = False) -> None:
        self.content_text = content
        self.pending = pending
        self.refresh(layout=True)

    def render(self) -> str:
        color = self.COLORS.get(self.kind, "#9facc0")
        state = "  [#66758b]· receiving[/]" if self.pending else ""
        return (
            f"[bold {color}]{escape(self.speaker.upper())}[/]"
            f"[#66758b]  ·  {escape(self.timestamp)}[/]{state}\n"
            f"[#e7edf5]{escape(self.content_text)}[/]"
        )


class AssistantMessage(Vertical):
    """Plain, inexpensive streaming followed by one Markdown render."""

    def __init__(
        self,
        content: str,
        *,
        timestamp: str | None = None,
        pending: bool = False,
    ):
        super().__init__(classes="message assistant-message")
        self.content_text = content
        self.timestamp = _short_time(timestamp)
        self.pending = pending

    def _meta_markup(self) -> str:
        state = "  [#66758b]· receiving[/]" if self.pending else ""
        return (
            f"[bold #39d5ff]{escape(CONFIG.assistant_name.upper())}[/]"
            f"[#66758b]  ·  {escape(self.timestamp)}[/]{state}"
        )

    def compose(self) -> ComposeResult:
        yield Static(self._meta_markup(), classes="assistant-meta")
        yield Static(
            self.content_text,
            markup=False,
            classes="assistant-stream-body",
        )
        yield Markdown(
            "" if self.pending else self.content_text,
            classes="assistant-markdown-body",
            open_links=False,
        )

    def on_mount(self) -> None:
        stream = self.query_one(".assistant-stream-body", Static)
        rendered = self.query_one(".assistant-markdown-body", Markdown)
        stream.display = self.pending
        rendered.display = not self.pending
        self.call_after_refresh(self._normalize_markdown_spacing)

    def _normalize_markdown_spacing(self) -> None:
        """Prevent expanded inline spans from becoming visually justified."""
        rendered = self.query_one(".assistant-markdown-body", Markdown)
        for block in rendered.query(Static):
            block.expand = False

    def set_stream_content(self, content: str) -> None:
        self.content_text = content
        self.pending = True
        self.query_one(".assistant-meta", Static).update(self._meta_markup())
        self.query_one(".assistant-stream-body", Static).update(content)
        self.query_one(".assistant-stream-body", Static).display = True
        self.query_one(".assistant-markdown-body", Markdown).display = False

    async def finalize(self, content: str) -> None:
        self.content_text = content
        self.pending = False
        self.query_one(".assistant-meta", Static).update(self._meta_markup())
        rendered = self.query_one(".assistant-markdown-body", Markdown)
        await rendered.update(content)
        self._normalize_markdown_spacing()
        self.query_one(".assistant-stream-body", Static).display = False
        rendered.display = True


class SandboxTerminal(Collapsible):
    def __init__(self, output_text: str):
        self.output_text = output_text
        collapsed = len(output_text) > 700 or output_text.count("\n") > 12
        super().__init__(
            Static(output_text.strip(), markup=False, classes="diagnostic-body"),
            title="AGENT SANDBOX",
            collapsed=collapsed,
            collapsed_symbol="▸",
            expanded_symbol="▾",
            classes="message tool-message diagnostic-panel",
        )


class SystemPanel(Collapsible):
    def __init__(self, title: str, output_text: str, warning: bool = False):
        self.panel_title = title
        self.output_text = output_text
        self.warning = warning
        classes = "system-panel warning" if warning else "system-panel"
        collapsed = not warning and (
            len(output_text) > 800 or output_text.count("\n") > 12
        )
        super().__init__(
            Static(output_text.strip(), markup=False, classes="diagnostic-body"),
            title=title.upper(),
            collapsed=collapsed,
            collapsed_symbol="▸",
            expanded_symbol="▾",
            classes=classes,
        )


class VerifiedFactPanel(Static):
    """Controller-rendered fact evidence; it never depends on model wording."""

    def __init__(self, fact: dict[str, Any]):
        subject = escape(str(fact.get("subject") or "Verified fact"))
        value = escape(str(fact.get("value") or "unavailable"))
        channel = escape(str(fact.get("channel") or "verified"))
        provider = escape(str(fact.get("provider") or "official source"))
        retrieved = escape(str(fact.get("retrieved_at") or ""))
        source_id = int(fact.get("source_id") or 1)
        source_url = escape(str(fact.get("source_url") or ""))
        evidence = escape(str(fact.get("evidence") or ""))
        content = (
            "[bold #67e8c2]✓ VERIFIED FACT[/]  "
            f"[#8492a8]{subject} · {channel}[/]\n"
            f"[bold #e7edf5]{value}[/]\n"
            f"[#c39aff][{source_id}] {provider}[/]  [#66758b]{retrieved}[/]\n"
            f"[#aab6c6]{evidence}[/]\n"
            f"[#66758b]{source_url}[/]"
        )
        super().__init__(content, classes="verified-fact-card")


class IntermixTUI(App):
    TITLE = f"{CONFIG.project_name} · {CONFIG.assistant_name}"
    ENABLE_COMMAND_PALETTE = False
    BINDINGS = [
        ("ctrl+q", "quit", "Quit"),
        ("ctrl+l", "clear_cockpit", "Clear"),
        ("ctrl+e", "toggle_workspace", "Files"),
        ("ctrl+v", "toggle_voice_archive", "Voice archive"),
        ("f2", "toggle_workspace", "Files"),
        ("ctrl+s", "save_workspace_file", "Save"),
        ("ctrl+r", "refresh_workspace", "Refresh"),
        ("ctrl+x", "cancel_operation", "Cancel"),
    ]

    CSS = """
    Screen {
        background: #060a12;
        color: #e7edf5;
    }

    #shell {
        width: 100%;
        height: 100%;
        background: #060a12;
    }

    #top-rail {
        height: 3;
        padding: 0 1;
        background: #0a1120;
        border-bottom: solid #243552;
    }

    #brand {
        width: 1fr;
        content-align: left middle;
        color: #39d5ff;
        text-style: bold;
    }

    #phase-indicator {
        width: 22;
        content-align: center middle;
        background: #0d1728;
    }

    #clock {
        width: 8;
        content-align: right middle;
        color: #7e8ca2;
    }

    #content-grid {
        height: 1fr;
        padding: 1;
    }

    #sidebar {
        width: 28;
        min-width: 28;
        height: 100%;
        padding: 0 1;
        margin-right: 1;
        background: #09101c;
        border-right: solid #263854;
    }

    .rail-title {
        height: 2;
        margin-top: 1;
        color: #a970ff;
        text-style: bold;
    }

    .rail-copy {
        color: #9eabbd;
    }

    #memory-telemetry {
        color: #c5cfdd;
    }

    #voice-telemetry {
        height: 2;
        margin-top: 1;
        color: #6272a4;
    }

    #command-list {
        color: #8492a8;
    }

    #conversation-panel {
        width: 1fr;
        height: 100%;
    }

    #workspace-panel {
        display: none;
        width: 1fr;
        height: 100%;
        background: #070c15;
    }

    #workspace-header {
        height: 3;
        padding: 0 1;
        background: #0b1423;
        border-bottom: solid #39d5ff;
    }

    #workspace-title {
        width: 1fr;
        content-align: left middle;
        color: #39d5ff;
        text-style: bold;
    }

    #workspace-mode {
        width: 30;
        content-align: right middle;
        color: #a970ff;
    }

    #workspace-body {
        height: 1fr;
        width: 100%;
    }

    #file-tree, #voice-file-tree {
        width: 32;
        min-width: 22;
        height: 100%;
        background: #09101c;
        border-right: solid #263854;
        scrollbar-color: #34506f;
        scrollbar-background: #09101c;
    }

    #file-editor {
        width: 1fr;
        height: 100%;
        background: #070d17;
        color: #e7edf5;
        border: none;
    }

    #workspace-footer {
        height: 3;
        padding: 0 1;
        background: #0c1422;
        border-top: solid #a970ff;
    }

    #file-status {
        width: 1fr;
        content-align: left middle;
        color: #9eabbd;
    }

    #file-hints {
        width: 34;
        content-align: right middle;
        color: #66758b;
    }

    #transcript {
        width: 100%;
        height: 1fr;
        padding: 0 1;
        background: #070c15;
    }

    #welcome {
        height: auto;
        padding: 1 1;
        margin-bottom: 1;
        background: #09111e;
        border-left: thick #39d5ff;
    }

    .message {
        width: 100%;
        height: auto;
        padding: 0 1 1 1;
        margin-bottom: 1;
        background: #0a101b;
    }

    .user-message {
        border-left: thick #a970ff;
        background: #0d1020;
    }

    .assistant-message {
        border-left: thick #39d5ff;
        background: #08131d;
        padding-bottom: 0;
    }

    .assistant-meta {
        width: 100%;
        height: auto;
        color: #39d5ff;
        margin-bottom: 1;
    }

    .assistant-stream-body {
        width: 100%;
        height: auto;
        color: #dbe4ef;
        padding-bottom: 1;
    }

    .assistant-markdown-body {
        width: 100%;
        height: auto;
        color: #dbe4ef;
        padding: 0;
    }

    .assistant-markdown-body MarkdownParagraph {
        color: #dbe4ef;
        margin: 0 0 1 0;
    }

    .assistant-markdown-body MarkdownH1 {
        color: #39d5ff;
        background: #0b1d2a;
        text-style: bold;
        content-align: left middle;
        padding: 0 1;
        margin: 1 0;
    }

    .assistant-markdown-body MarkdownH2 {
        color: #c39aff;
        background: transparent;
        text-style: bold;
        border-bottom: solid #3b2c5c;
        margin: 1 0;
    }

    .assistant-markdown-body MarkdownH3,
    .assistant-markdown-body MarkdownH4 {
        color: #67e8c2;
        background: transparent;
        text-style: bold;
        margin: 1 0 0 0;
    }

    .assistant-markdown-body MarkdownFence {
        color: #d6dfeb;
        background: #050a11;
        border-left: solid #a970ff;
        margin: 1 0;
    }

    .assistant-markdown-body MarkdownBlockQuote {
        color: #b9c6d7;
        background: #0b1423;
        border-left: solid #39d5ff;
    }

    .system-message {
        border-left: thick #ffca6b;
    }

    .tool-message {
        border-left: thick #ffca6b;
        background: #16130f;
    }

    .verified-fact-card {
        width: 100%;
        height: auto;
        padding: 1 2;
        margin-bottom: 1;
        color: #e7edf5;
        background: #081821;
        border-left: thick #67e8c2;
        border-top: solid #173848;
        border-bottom: solid #2e2350;
    }

    .system-panel {
        width: 100%;
        height: auto;
        padding: 0 0 0 1;
        margin-bottom: 1;
        color: #9eabbd;
        border-left: solid #40516b;
        border-top: none;
        background: transparent;
    }

    .diagnostic-panel {
        height: auto;
        border-top: none;
        padding: 0 0 0 1;
    }

    .system-panel CollapsibleTitle {
        color: #8290a5;
        background: transparent;
        text-style: bold;
        padding: 0;
    }

    .warning CollapsibleTitle {
        color: #ff8ca8;
    }

    .tool-message CollapsibleTitle {
        color: #ffca6b;
        background: transparent;
        text-style: bold;
        padding: 0;
    }

    .system-panel > Contents,
    .diagnostic-panel > Contents {
        height: auto;
        padding: 1 1 0 2;
    }

    .diagnostic-body {
        width: 100%;
        height: auto;
        color: #aab6c6;
    }

    .warning {
        border-left: thick #ff6f91;
        background: #1b0d16;
    }

    #activity-rail {
        height: 2;
        padding: 0 1;
        background: #080e18;
        border-top: solid #1d2b43;
    }

    #activity {
        width: 1fr;
        content-align: left middle;
        color: #8e9caf;
    }

    #latency {
        width: 18;
        content-align: right middle;
        color: #66758b;
    }

    #composer {
        height: 3;
        padding: 0 1;
        background: #0c1422;
        border-top: solid #a970ff;
    }

    #input-box {
        width: 1fr;
        height: 100%;
        background: transparent;
        border: none;
        padding: 0;
        color: #f0f4f8;
        scrollbar-size-vertical: 1;
        scrollbar-color: #34506f;
        scrollbar-background: #101a2a;
    }

    #input-box:focus {
        border: none;
        background: #101a2a;
    }

    #input-box .text-area--cursor {
        color: #060a12;
        background: #39d5ff;
    }

    #input-box .text-area--selection {
        background: #3b2464;
    }

    #input-box .text-area--placeholder {
        color: #66758b;
    }

    #voice-last {
        display: none;
        width: 12;
        height: 3;
        min-width: 12;
        border: none;
        background: #183449;
        color: #67e8c2;
        text-style: bold;
    }

    #voice-last:disabled {
        background: #111a28;
        color: #536276;
    }

    #send-hint {
        width: 19;
        content-align: right middle;
        color: #66758b;
    }
    """

    def __init__(self):
        super().__init__()
        self.busy = False
        self.phase_started = 0.0
        self.phase_name = "ready"
        self.phase_detail = "Local memory online"
        self._telemetry_tick = 0
        self.workspace_visible = False
        self.current_file: Path | None = None
        self.editor_original = ""
        self.editor_loading = False
        self.last_user_activity = time.monotonic()
        self.last_maintenance_launch = 0.0
        self.maintenance_task: asyncio.Task | None = None
        self.voice_text = ""
        self.voice_generation_complete = False
        self.voice_request_id = ""
        self.voice_playback_active = False
        self.voice_archive_visible = False

    def compose(self) -> ComposeResult:
        with Vertical(id="shell"):
            with Horizontal(id="top-rail"):
                yield Static(
                    f"◈  {escape(CONFIG.project_name.upper())}  /  COGNITION",
                    id="brand",
                )
                yield Static("[#67e8c2]●  READY[/]", id="phase-indicator")
                yield Static(datetime.now().strftime("%H:%M"), id="clock")
            with Horizontal(id="content-grid"):
                with Vertical(id="sidebar"):
                    yield Label("CORE", classes="rail-title")
                    librarian_state = (
                        "ready"
                        if CONFIG.dual_model_enabled and CONFIG.librarian_model_path.is_file()
                        else "disabled"
                        if not CONFIG.dual_model_enabled
                        else "optional"
                    )
                    yield Static(
                        f"[#39d5ff]{escape(CONFIG.model_label)} · reasoning[/]\n"
                        f"[#a970ff]{escape(CONFIG.librarian_model_label)} · {librarian_state}[/]\n"
                        "[#8997aa]LiteRT-LM · one resident[/]",
                        classes="rail-copy",
                    )
                    yield Label("MEMORY MATRIX", classes="rail-title")
                    yield Static("Synchronizing…", id="memory-telemetry")
                    yield Static("[#6272a4]VOICE: OFFLINE 🔇[/]", id="voice-telemetry")
                    yield Label("COMMANDS", classes="rail-title")
                    yield Static(
                        "[#39d5ff]/web[/]     grounded answer\n"
                        "[#39d5ff]/web status[/] providers\n"
                        "[#39d5ff]/workspace[/] agent loop\n"
                        "[#39d5ff]/create[/]   long-form studio\n"
                        "[#39d5ff]/files[/]    workspace lens\n"
                        "[#a970ff]/task status[/] mission\n"
                        "[#a970ff]/approvals[/] deletions\n"
                        "[#a970ff]/memory facts[/] recall\n"
                        "[#a970ff]/model status[/] routing\n"
                        "[#a970ff]/persona status[/] identity\n"
                        "[#a970ff]/sanctuary status[/] vault\n"
                        "[#a970ff]/sessions[/] timeline\n"
                        "[#a970ff]/engine status[/] runtime\n"
                        "[#a970ff]/status[/] diagnostics\n"
                        "[#67e8c2]CTRL+V[/]   voice archive",
                        id="command-list",
                    )
                with Vertical(id="conversation-panel"):
                    with VerticalScroll(id="transcript"):
                        yield Static(WELCOME, id="welcome")
                    with Horizontal(id="activity-rail"):
                        yield Static("Local memory online", id="activity")
                        yield Static("", id="latency")
                    with Horizontal(id="composer"):
                        yield MessageComposer(
                            placeholder=f"Message {CONFIG.assistant_name} or type / for commands",
                            id="input-box",
                        )
                        yield Button("VOICE", id="voice-last", disabled=True)
                        yield Static("ENTER SEND\nSHIFT+ENTER LINE", id="send-hint")
                with Vertical(id="workspace-panel"):
                    with Horizontal(id="workspace-header"):
                        yield Static("WORKSPACE LENS", id="workspace-title")
                        yield Static("COLLABORATE · DELETE REVIEW", id="workspace-mode")
                    with Horizontal(id="workspace-body"):
                        yield WorkspaceTree(WORKSPACE_DIR, id="file-tree")
                        yield VoiceArchiveTree(AUDIO_ARCHIVE_DIR, id="voice-file-tree")
                        editor = TextArea.code_editor(
                            "",
                            theme="css",
                            id="file-editor",
                        )
                        editor.register_theme(INTERMIX_EDITOR_THEME)
                        editor.theme = INTERMIX_EDITOR_THEME.name
                        yield editor
                    with Horizontal(id="workspace-footer"):
                        yield Static("Select a file to inspect or edit", id="file-status")
                        yield Static("CTRL+S SAVE · CTRL+E CHAT", id="file-hints")

    async def on_mount(self) -> None:
        self.query_one("#workspace-panel", Vertical).display = False
        self.query_one("#voice-file-tree", VoiceArchiveTree).display = False
        self.query_one("#file-editor", TextArea).read_only = True
        await self._load_history(clear_first=False)
        self._refresh_telemetry()
        self._apply_responsive_layout(self.size.width)
        self.set_interval(0.25, self._tick_status)
        self.run_worker(
            self._monitor_audio_bridge(),
            group="audio-bridge-monitor",
            exclusive=True,
            name="audio-bridge-monitor",
        )
        self._tick_audio_bridge()
        self.query_one("#input-box", MessageComposer).focus()
        self.call_after_refresh(self._resize_composer)

    def on_unmount(self) -> None:
        cancel_idle_maintenance()
        if self.voice_request_id:
            cancel_request_playback(self.voice_request_id)
        shutdown_inference()

    def on_resize(self, event: events.Resize) -> None:
        self._apply_responsive_layout(event.size.width)
        self.call_after_refresh(self._resize_composer)

    def on_key(self, event: events.Key) -> None:
        self.last_user_activity = time.monotonic()
        if self.maintenance_task and not self.maintenance_task.done():
            cancel_idle_maintenance()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "voice-last":
            if self.busy:
                self.action_cancel_operation()
            else:
                self._queue_completed_voice()

    async def _monitor_audio_bridge(self) -> None:
        while True:
            status = await asyncio.to_thread(bridge_status)
            self._apply_audio_bridge_status(status)
            await asyncio.sleep(1.0 if self.voice_request_id else 5.0)

    def _tick_audio_bridge(self) -> None:
        self._apply_audio_bridge_status(bridge_status())

    def _apply_audio_bridge_status(self, status: dict[str, Any]) -> None:
        button = self.query_one("#voice-last", Button)
        telemetry = self.query_one("#voice-telemetry", Static)
        if self.busy:
            telemetry.update(
                "[bold #50fa7b]VOICE: ONLINE 🔊[/]"
                if status["connected"]
                else "[#6272a4]VOICE: OFFLINE 🔇[/]"
            )
            button.display = True
            button.label = "STOP"
            button.disabled = False
            return
        button.display = bool(status["connected"])
        if not status["connected"]:
            telemetry.update("[#6272a4]VOICE: OFFLINE 🔇[/]")
            button.disabled = True
            return
        telemetry.update("[bold #50fa7b]VOICE: ONLINE 🔊[/]")
        if not self.voice_request_id and not self.voice_playback_active:
            self.voice_request_id = next_audio_ready_request()
        if self.voice_request_id:
            result = request_result(self.voice_request_id)
            if result is None:
                claimed = status.get("active_request_id") == self.voice_request_id
                button.label = "RENDERING" if claimed else "QUEUED"
                if claimed:
                    self.query_one("#activity", Static).update(
                        "Kokoro Core is rendering the complete response…"
                    )
                button.disabled = True
                return
            outcome = str(result.get("status", "failed"))
            if outcome == "audio_ready":
                button.label = "STARTING"
                button.disabled = True
                self.query_one("#activity", Static).update(
                    "Verified WAV received; starting Android playback…"
                )
                if not self.voice_playback_active:
                    claimed_path = claim_audio_ready(self.voice_request_id)
                    if claimed_path is not None:
                        self.voice_playback_active = True
                        self.run_worker(
                            self._play_voice_request(self.voice_request_id),
                            group="termux-audio-playback",
                            exclusive=True,
                            name="termux-audio-playback",
                        )
                return
            if outcome == "starting":
                button.label = "STARTING"
                button.disabled = True
                return
            if outcome == "playing":
                button.label = "PLAYING"
                button.disabled = True
                self.query_one("#activity", Static).update("Kokoro voice is playing through Android 🔊")
                return
            if outcome == "completed":
                self.voice_request_id = ""
                self.voice_playback_active = False
                self.query_one("#activity", Static).update("Kokoro playback completed 🔊")
                button.label = "VOICE AGAIN"
            else:
                self.voice_request_id = ""
                self.voice_playback_active = False
                self.query_one("#activity", Static).update(
                    f"Kokoro voice {outcome}: {result.get('detail', '')}"
                )
                button.label = "RETRY VOICE"
        else:
            button.label = "VOICE"
        button.disabled = not bool(
            self.voice_generation_complete and self.voice_text and not self.busy
        )

    def _set_generation_controls(self, active: bool) -> None:
        button = self.query_one("#voice-last", Button)
        if active:
            button.display = True
            button.label = "STOP"
            button.disabled = False
            return
        self._tick_audio_bridge()

    async def _play_voice_request(self, request_id: str) -> None:
        try:
            await play_claimed_request(request_id)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self.query_one("#activity", Static).update(f"Android playback failed: {exc}")
        finally:
            self.voice_playback_active = False
            self._tick_audio_bridge()

    def _queue_completed_voice(self) -> None:
        if not self.voice_generation_complete or not self.voice_text or self.busy:
            return
        session = get_store().get_active_session(create=False)
        try:
            self.voice_request_id = submit_completed_response(
                self.voice_text,
                generation_complete=True,
                session_id=str(session.get("id", "")) if session else "",
            )
        except Exception as exc:
            self.query_one("#activity", Static).update(f"Voice bridge unavailable: {exc}")
            return
        button = self.query_one("#voice-last", Button)
        button.label = "QUEUED"
        button.disabled = True
        self.query_one("#activity", Static).update(
            "Transmitting completed response to Kokoro Core…"
        )

    async def action_toggle_voice_archive(self) -> None:
        if self.workspace_visible and self.voice_archive_visible:
            self.workspace_visible = False
            self.voice_archive_visible = False
            self.query_one("#workspace-panel", Vertical).display = False
            self.query_one("#conversation-panel", Vertical).display = True
            self.query_one("#input-box", MessageComposer).focus()
            self.query_one("#brand", Static).update("◈  PROJECT INTERMIX  /  RESONANCE")
            return
        self.workspace_visible = True
        self.voice_archive_visible = True
        self.current_file = None
        self.query_one("#conversation-panel", Vertical).display = False
        self.query_one("#workspace-panel", Vertical).display = True
        self.query_one("#file-tree", WorkspaceTree).display = False
        voice_tree = self.query_one("#voice-file-tree", VoiceArchiveTree)
        voice_tree.display = self.size.width >= 72
        await voice_tree.reload()
        voice_tree.focus()
        self.query_one("#workspace-title", Static).update("VOICE ARCHIVE")
        self.query_one("#workspace-mode", Static).update("READ ONLY · 25 ROTATING + PINS")
        self.query_one("#file-hints", Static).update("CTRL+V CHAT · CTRL+E WORKSPACE")
        self.query_one("#brand", Static).update("◈  PROJECT INTERMIX  /  VOICE ARCHIVE")

    def _open_voice_file(self, path: Path) -> None:
        editor = self.query_one("#file-editor", TextArea)
        try:
            detail = audio_archive_details(path)
        except Exception as exc:
            self.query_one("#file-status", Static).update(
                f"Audio inspection failed · {type(exc).__name__}: {exc}"
            )
            return
        duration = detail.get("duration_seconds")
        rate = detail.get("sample_rate")
        duration_text = f"{duration:.1f} seconds" if isinstance(duration, (int, float)) else "unavailable"
        rendered = (
            "VOICE ARCHIVE RECORDING\n\n"
            f"File: {detail['name']}\n"
            f"Duration: {duration_text}\n"
            f"Sample rate: {rate or 'unavailable'} Hz\n"
            f"Channels: {detail.get('channels') or 'unavailable'}\n"
            f"Size: {int(detail['size_bytes']) / (1024 * 1024):.2f} MiB\n"
            f"Pinned: {'yes' if detail.get('pinned') else 'no'}\n\n"
            "This managed recording is read-only in Intermix. Exports outside this archive are never rotated."
        )
        self.editor_loading = True
        editor.text = rendered
        editor.read_only = True
        self.current_file = None
        self.editor_original = rendered
        self.editor_loading = False
        self.query_one("#file-status", Static).update(
            f"{detail['name']} · managed voice archive"
        )

    def _apply_responsive_layout(self, width: int) -> None:
        self.query_one("#sidebar", Vertical).display = width >= 96
        self.query_one("#send-hint", Static).display = width >= 72
        show_tree = width >= 72 and self.workspace_visible
        self.query_one("#file-tree", WorkspaceTree).display = bool(
            show_tree and not self.voice_archive_visible
        )
        self.query_one("#voice-file-tree", VoiceArchiveTree).display = bool(
            show_tree and self.voice_archive_visible
        )
        self.query_one("#workspace-mode", Static).display = width >= 86

    def _resize_composer(self) -> None:
        """Grow with wrapped content, cap at seven rows, and then scroll."""

        composer = self.query_one("#input-box", MessageComposer)
        wrapped_rows = max(
            composer.document.line_count,
            composer.wrapped_document.height,
            1,
        )
        height = max(
            COMPOSER_COLLAPSED_HEIGHT,
            min(COMPOSER_MAX_VISIBLE_LINES, wrapped_rows) + COMPOSER_BORDER_HEIGHT,
        )
        self.query_one("#composer", Horizontal).styles.height = height

    async def action_clear_cockpit(self) -> None:
        await self._clear_transcript()

    async def action_toggle_workspace(self) -> None:
        opening = not self.workspace_visible or self.voice_archive_visible
        self.workspace_visible = opening
        self.voice_archive_visible = False
        self.query_one("#conversation-panel", Vertical).display = not opening
        self.query_one("#workspace-panel", Vertical).display = opening
        self.last_user_activity = time.monotonic()
        cancel_idle_maintenance()
        if opening:
            self.query_one("#voice-file-tree", VoiceArchiveTree).display = False
            self.query_one("#file-tree", WorkspaceTree).display = self.size.width >= 72
            self.query_one("#file-tree", WorkspaceTree).focus()
            self.query_one("#workspace-title", Static).update("WORKSPACE LENS")
            self.query_one("#workspace-mode", Static).update("COLLABORATE · DELETE REVIEW")
            self.query_one("#file-hints", Static).update("CTRL+S SAVE · CTRL+E CHAT · CTRL+V AUDIO")
            self.query_one("#brand", Static).update("◈  PROJECT INTERMIX  /  WORKSPACE")
        else:
            self.query_one("#input-box", MessageComposer).focus()
            self.query_one("#brand", Static).update("◈  PROJECT INTERMIX  /  RESONANCE")

    async def action_refresh_workspace(self) -> None:
        if self.voice_archive_visible:
            tree = self.query_one("#voice-file-tree", VoiceArchiveTree)
            await tree.reload()
            self.query_one("#file-status", Static).update("Voice archive refreshed")
        else:
            tree = self.query_one("#file-tree", WorkspaceTree)
            await tree.reload()
            self.query_one("#file-status", Static).update("Workspace tree refreshed")

    async def action_save_workspace_file(self) -> None:
        if not self.workspace_visible or self.current_file is None:
            return
        editor = self.query_one("#file-editor", TextArea)
        if editor.read_only:
            self.query_one("#file-status", Static).update("Selected file is read-only")
            return
        if editor.text == self.editor_original:
            self.query_one("#file-status", Static).update(
                f"{relative_path(self.current_file)} · no unsaved changes"
            )
            return
        try:
            event = await asyncio.to_thread(
                write_text,
                relative_path(self.current_file),
                editor.text,
                source="workspace_lens",
            )
            record_manual_workspace_event(event)
            self.editor_original = editor.text
            self.query_one("#file-status", Static).update(
                f"{event['path']} · saved with recoverable checkpoint"
            )
            await self.action_refresh_workspace()
        except Exception as exc:
            self.query_one("#file-status", Static).update(
                f"Save failed · {type(exc).__name__}: {exc}"
            )

    def action_cancel_operation(self) -> None:
        voice_cancelled = bool(
            self.voice_request_id and cancel_request_playback(self.voice_request_id)
        )
        cancelled = cancel_active_operations() or voice_cancelled
        self.query_one("#activity", Static).update(
            "Stopping current operation safely…"
            if cancelled
            else "No cancellable operation is active"
        )

    def _open_workspace_file(self, path: Path) -> None:
        editor = self.query_one("#file-editor", TextArea)
        try:
            safe = safe_path(path, must_exist=True)
            data, editable, reason = read_text(safe, max_bytes=MAX_EDITOR_BYTES)
        except Exception as exc:
            self.query_one("#file-status", Static).update(
                f"Open failed · {type(exc).__name__}: {exc}"
            )
            return
        self.editor_loading = True
        editor.text = data
        editor.read_only = not editable
        self.current_file = safe
        self.editor_original = data
        self.editor_loading = False
        status = f"{relative_path(safe)} · {safe.stat().st_size} bytes"
        if reason:
            status += f" · {reason}"
        self.query_one("#file-status", Static).update(status)

    def on_directory_tree_file_selected(self, event: DirectoryTree.FileSelected) -> None:
        self.last_user_activity = time.monotonic()
        cancel_idle_maintenance()
        selected = Path(event.path)
        try:
            is_audio = selected.resolve().parent == AUDIO_ARCHIVE_DIR.resolve()
        except OSError:
            is_audio = False
        if is_audio:
            self._open_voice_file(selected)
        else:
            self._open_workspace_file(selected)

    def on_text_area_changed(self, event: TextArea.Changed) -> None:
        if event.text_area.id == "input-box":
            self.call_after_refresh(self._resize_composer)
            return
        if event.text_area.id != "file-editor":
            return
        if self.editor_loading or self.current_file is None:
            return
        self.last_user_activity = time.monotonic()
        cancel_idle_maintenance()
        editor = self.query_one("#file-editor", TextArea)
        if editor.text != self.editor_original:
            self.query_one("#file-status", Static).update(
                f"{relative_path(self.current_file)} · modified · Ctrl+S to save"
            )

    def _tick_status(self) -> None:
        clocks = list(self.query("#clock"))
        if not clocks:
            return
        self._telemetry_tick += 1
        clocks[0].update(datetime.now().strftime("%H:%M"))
        agent = get_agent_runtime_status()
        if agent.get("state") == "running":
            action = str(agent.get("action") or "workspace action")
            path = str(agent.get("path") or "")
            self.query_one("#activity", Static).update(
                escape(f"{action} {path}".strip()) + "  ·  Ctrl+X cancel"
            )
            self.query_one("#latency", Static).update(
                f"{float(agent.get('elapsed_seconds', 0)):.1f}s tool"
            )
        elif self.busy and self.phase_started:
            elapsed = time.monotonic() - self.phase_started
            self.query_one("#latency", Static).update(f"{elapsed:5.1f}s elapsed")
        elif not self.busy and self._telemetry_tick % 8 == 0:
            engine = get_engine_status()
            label = "resident hot" if engine["loaded"] else "resident cold"
            self.query_one("#latency", Static).update(label)
        if self._telemetry_tick % 20 == 0:
            self._refresh_telemetry()
            if self.maintenance_task and self.maintenance_task.done():
                try:
                    result = self.maintenance_task.result()
                    detail = result.get("documentation", {})
                    changed = len(detail.get("changed_paths", [])) if isinstance(detail, dict) else 0
                    self.query_one("#activity", Static).update(
                        f"Idle maintenance complete · {changed} structural changes"
                    )
                except Exception as exc:
                    self.query_one("#activity", Static).update(
                        f"Idle maintenance error · {type(exc).__name__}"
                    )
                self.maintenance_task = None

            idle_for = time.monotonic() - self.last_user_activity
            since_launch = time.monotonic() - self.last_maintenance_launch
            if (
                not self.busy
                and agent.get("state") != "running"
                and self.maintenance_task is None
                and idle_for >= IDLE_SECONDS
                and (
                    self.last_maintenance_launch == 0.0
                    or since_launch >= MAINTENANCE_INTERVAL_SECONDS
                )
            ):
                self.last_maintenance_launch = time.monotonic()
                self.maintenance_task = asyncio.create_task(
                    asyncio.to_thread(run_idle_maintenance)
                )

    def _set_phase(self, name: str, detail: str = "", metrics: dict[str, Any] | None = None) -> None:
        normalized = name if name in PHASE_STYLES else "generating"
        label, color = PHASE_STYLES[normalized]
        self.phase_name = normalized
        self.phase_detail = detail or label.title()
        self.query_one("#phase-indicator", Static).update(
            f"[bold {color}]●  {escape(label)}[/]"
        )
        self.query_one("#activity", Static).update(escape(self.phase_detail))
        if metrics and metrics.get("seconds") is not None:
            self.query_one("#latency", Static).update(
                f"{float(metrics['seconds']):.2f}s phase"
            )

    async def _clear_transcript(self) -> None:
        transcript = self.query_one("#transcript", VerticalScroll)
        await transcript.remove_children()
        await transcript.mount(Static(WELCOME, id="welcome"))

    async def _load_history(self, clear_first: bool = True) -> None:
        if clear_first:
            await self._clear_transcript()
        transcript = self.query_one("#transcript", VerticalScroll)
        messages = recent_transcript(limit=50)
        if messages:
            await transcript.mount(
                SystemPanel(
                    "Memory rehydrated",
                    f"{len(messages)} recent messages restored from the active session.",
                )
            )
        for message in messages:
            role = message["role"]
            if role == "user":
                await transcript.mount(
                    MessageBlock(
                        CONFIG.user_name,
                        message["content"],
                        kind="user",
                        timestamp=message.get("created_at"),
                    )
                )
            elif role == "assistant":
                await transcript.mount(
                    AssistantMessage(
                        message["content"],
                        timestamp=message.get("created_at"),
                    )
                )
            elif role == "tool":
                await transcript.mount(SandboxTerminal(message["content"]))
            elif role == "system":
                await transcript.mount(SystemPanel(message["speaker"], message["content"], warning=True))
        transcript.scroll_end(animate=False)

    def _refresh_telemetry(self) -> None:
        status = get_store().status()
        report = get_store().latest_prompt_report(str(status["active_session"]))
        engine = get_engine_status()
        prompt_tokens = int(report["input_tokens"]) if report else 0
        report_detail = report.get("report", {}) if report else {}
        prompt_limit = int(report_detail.get("input_limit", INPUT_LIMIT_TOKENS))
        response_mode = str(report_detail.get("response_mode", get_store().get_setting("response_mode", "auto")))
        task = get_active_task_status()
        memory_mb = engine.get("available_memory_mb")
        if memory_mb is None:
            memory_text = "unknown"
            memory_color = "#9eabbd"
        else:
            memory_text = f"{memory_mb / 1024:.1f} GiB free"
            memory_color = (
                "#ff8ca8" if memory_mb < engine.get("critical_memory_mb", 768)
                else "#ffca6b" if memory_mb < engine.get("low_memory_mb", 1536)
                else "#c5cfdd"
        )
        loaded = "HOT" if engine["loaded"] else "COLD"
        active_profile = str(engine.get("active_profile") or "").upper()
        engine_line = f"Engine   {engine['mode'].upper()} · {loaded}"
        if active_profile:
            engine_line += f" · {escape(active_profile[:3])}"
        task_line = (
            f"Mission  {escape(str(task.get('status', '')).upper())}\n"
            if task
            else ""
        )
        text = (
            f"[#39d5ff]{escape(status['active_title'] or 'No session')}[/]\n"
            f"Prompt  {prompt_tokens:>4} / {prompt_limit}\n"
            f"KV      {ENGINE_CONTEXT_TOKENS}\n"
            f"Messages {status['messages']}\n"
            f"Memories {status['memories']}\n"
            f"Timeline {status.get('events', 0)}\n"
            f"Reply    {escape(response_mode.upper())}\n"
            f"{task_line}"
            f"{engine_line}\n"
            f"System   [{memory_color}]{memory_text}[/]"
        )
        self.query_one("#memory-telemetry", Static).update(text)

    async def _show_local_result(self, command_text: str, result: dict[str, Any]) -> None:
        transcript = self.query_one("#transcript", VerticalScroll)
        action = result.get("action")
        if action == "clean":
            await self._clear_transcript()
            await transcript.mount(SystemPanel("Memory preserved", result["output"]))
        elif action == "reload":
            await self._load_history(clear_first=True)
            await transcript.mount(SystemPanel("Session matrix", result["output"]))
        elif action == "workspace":
            await transcript.mount(MessageBlock(CONFIG.user_name, command_text, kind="user"))
            await transcript.mount(SystemPanel("Workspace Lens", result.get("output", "")))
            await self.action_toggle_workspace()
        elif action in {"research_scan", "docs_refresh", "report_generate"}:
            await transcript.mount(MessageBlock(CONFIG.user_name, command_text, kind="user"))
            await transcript.mount(SystemPanel("Local maintenance", result.get("output", "")))
            self.busy = True
            self.phase_started = time.monotonic()
            self._set_phase("searching" if action == "research_scan" else "assembling", result.get("output", ""))
            try:
                if action == "research_scan":
                    output = await asyncio.to_thread(run_idle_maintenance, force_research=True)
                elif action == "report_generate":
                    output = await asyncio.to_thread(generate_idle_report, get_store(), force=True)
                else:
                    output = await asyncio.to_thread(update_project_documentation)
                await transcript.mount(
                    SystemPanel("Maintenance result", json.dumps(output, indent=2, ensure_ascii=False))
                )
            finally:
                self.busy = False
                self.phase_started = 0.0
                self._set_phase("ready", "Local maintenance synchronized")
        else:
            await transcript.mount(MessageBlock(CONFIG.user_name, command_text, kind="user"))
            await transcript.mount(SystemPanel("Local system", result.get("output", "")))
        self._refresh_telemetry()
        transcript.scroll_end(animate=False)

    async def on_message_composer_submitted(
        self,
        event: MessageComposer.Submitted,
    ) -> None:
        input_widget = event.composer
        transcript = self.query_one("#transcript", VerticalScroll)
        user_text = event.value.strip()
        if not user_text or self.busy:
            return
        input_widget.load_text("")
        self.call_after_refresh(self._resize_composer)
        self.last_user_activity = time.monotonic()
        cancel_idle_maintenance()
        self.voice_text = ""
        self.voice_generation_complete = False
        self.voice_request_id = ""
        self._tick_audio_bridge()

        command_result = local_command(user_text)
        if command_result.get("handled"):
            await self._show_local_result(user_text, command_result)
            return

        await transcript.mount(MessageBlock(CONFIG.user_name, user_text, kind="user"))
        ai_widget = AssistantMessage(
            "Preparing local context…",
            pending=True,
        )
        await transcript.mount(ai_widget)
        transcript.scroll_end(animate=False)

        self.busy = True
        self.phase_started = time.monotonic()
        self._set_phase("recalling", "Selecting durable context")
        input_widget.disabled = True
        self._set_generation_controls(True)
        self.run_worker(
            self._run_inference_turn(user_text, ai_widget),
            group="inference",
            exclusive=True,
            name="foreground-inference",
        )

    async def _run_inference_turn(
        self,
        user_text: str,
        ai_widget: AssistantMessage,
    ) -> None:
        input_widget = self.query_one("#input-box", MessageComposer)
        transcript = self.query_one("#transcript", VerticalScroll)
        accumulated = ""
        error_seen = False
        incomplete = False
        stop_detail = ""
        last_repaint = 0.0
        try:
            async for event_type, chunk in stream_inference(user_text):
                scroll_needed = False
                if event_type == "token":
                    accumulated += chunk
                    now = time.monotonic()
                    if now - last_repaint >= STREAM_REPAINT_SECONDS:
                        ai_widget.set_stream_content(accumulated)
                        last_repaint = now
                        scroll_needed = True
                elif event_type == "phase":
                    try:
                        phase = json.loads(chunk)
                    except json.JSONDecodeError:
                        phase = {"name": chunk, "detail": chunk}
                    self._set_phase(
                        str(phase.get("name", "generating")),
                        str(phase.get("detail", "")),
                        phase,
                    )
                elif event_type == "execution":
                    await transcript.mount(SandboxTerminal(chunk))
                    scroll_needed = True
                elif event_type == "workspace_event":
                    try:
                        workspace_event = json.loads(chunk)
                    except json.JSONDecodeError:
                        workspace_event = {}
                    changed_path = str(workspace_event.get("path", ""))
                    if changed_path and workspace_event.get("action") in {"write_file", "delete_file"}:
                        await self.query_one("#file-tree", WorkspaceTree).reload()
                        editor = self.query_one("#file-editor", TextArea)
                        if (
                            workspace_event.get("action") == "write_file"
                            and editor.text == self.editor_original
                        ):
                            try:
                                self._open_workspace_file(safe_path(changed_path, must_exist=True))
                            except Exception:
                                pass
                elif event_type == "task":
                    try:
                        task = json.loads(chunk)
                    except json.JSONDecodeError:
                        task = {}
                    status = str(task.get("status", "active"))
                    self.query_one("#activity", Static).update(
                        f"Mission {str(task.get('id', ''))} · {status}"
                    )
                    if task.get("created") or status in {"completed", "blocked"}:
                        await transcript.mount(
                            SystemPanel(
                                "Mission ledger",
                                f"{task.get('id', '')} · {status}\n{task.get('detail') or task.get('goal') or ''}",
                                warning=status == "blocked",
                            )
                        )
                        scroll_needed = True
                elif event_type == "approval":
                    try:
                        approval = json.loads(chunk)
                    except json.JSONDecodeError:
                        approval = {}
                    await transcript.mount(
                        SystemPanel(
                            "Deletion review required",
                            f"{approval.get('path', '')}\n/approve {approval.get('request_id', '')}  or  /deny {approval.get('request_id', '')}",
                            warning=True,
                        )
                    )
                    scroll_needed = True
                elif event_type == "grounding":
                    if chunk == "unavailable":
                        await transcript.mount(
                            SystemPanel(
                                "Grounding unavailable",
                                "No verifiable source was retrieved.",
                                warning=True,
                            )
                        )
                        scroll_needed = True
                    else:
                        source = "cached evidence" if chunk == "cached" else "live evidence"
                        self.query_one("#activity", Static).update(f"Linked {source}")
                elif event_type == "grounding_plan":
                    try:
                        plan = json.loads(chunk)
                    except json.JSONDecodeError:
                        plan = {}
                    execution = plan.get("execution", {}) if isinstance(plan, dict) else {}
                    if not isinstance(execution, dict):
                        execution = {}
                    query_count = len(execution.get("executed_queries", [])) or 1
                    requests = int(execution.get("requests_used", 0) or 0)
                    adaptive = bool(execution.get("adaptive_follow_up_used"))
                    label = "adaptive" if adaptive else "direct"
                    self.query_one("#activity", Static).update(
                        f"Grounding {label} · {query_count} quer{'y' if query_count == 1 else 'ies'} · {requests} requests"
                    )
                elif event_type == "fact_card":
                    try:
                        fact = json.loads(chunk)
                    except json.JSONDecodeError:
                        fact = {}
                    if fact:
                        await transcript.mount(VerifiedFactPanel(fact), before=ai_widget)
                        scroll_needed = True
                elif event_type == "context_compacted":
                    await transcript.mount(
                        SystemPanel(
                            "Virtual context",
                            f"Older turns compacted; {chunk} estimated input tokens retained.",
                        )
                    )
                    scroll_needed = True
                elif event_type == "next_step":
                    self._set_phase("generating", f"Agent repair step {chunk}")
                elif event_type == "generation_stopped":
                    incomplete = True
                    try:
                        stop = json.loads(chunk)
                    except json.JSONDecodeError:
                        stop = {"reason": "cancelled", "detail": chunk}
                    stop_detail = str(
                        stop.get("detail") or "Generation stopped before completion."
                    )
                    await transcript.mount(
                        SystemPanel(
                            "Generation stopped",
                            stop_detail
                            + "\nThe visible partial response was not written to memory.",
                            warning=True,
                        )
                    )
                    self._set_phase("stopping", stop_detail)
                    scroll_needed = True
                elif event_type == "error":
                    error_seen = True
                    await transcript.mount(SystemPanel("Inference guard", chunk, warning=True))
                    scroll_needed = True
                if scroll_needed:
                    transcript.scroll_end(animate=False)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            error_seen = True
            await transcript.mount(
                SystemPanel(
                    "Inference failure",
                    f"{type(exc).__name__}: {exc}",
                    warning=True,
                )
            )
        finally:
            final_text = accumulated or (
                "Generation stopped before a complete response was produced."
                if incomplete
                else (
                    "No speculative response was generated."
                    if error_seen
                    else "The local engine returned no visible response."
                )
            )
            await ai_widget.finalize(final_text)
            self.voice_text = (
                accumulated.strip()
                if accumulated.strip() and not error_seen and not incomplete
                else ""
            )
            self.voice_generation_complete = bool(self.voice_text)
            self.busy = False
            self.phase_started = 0.0
            input_widget.disabled = False
            input_widget.focus()
            self._set_phase(
                "ready",
                "Generation stopped safely" if incomplete else "Local memory synchronized",
            )
            self._refresh_telemetry()
            self._set_generation_controls(False)
            transcript.scroll_end(animate=False)


if __name__ == "__main__":
    IntermixTUI().run()
