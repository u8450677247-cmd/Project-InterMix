from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"

try:
    import textual  # noqa: F401
except ImportError:
    textual = None


@unittest.skipIf(textual is None, "Textual is verified by the device installer")
class WorkspaceLensTests(unittest.TestCase):
    def test_foreground_generation_exposes_stop_and_recovers_composer(self):
        with tempfile.TemporaryDirectory() as temp:
            env = os.environ.copy()
            env["HOME"] = temp
            env["PYTHONPATH"] = os.pathsep.join(
                [str(ENGINE), *[path for path in sys.path if path]]
            )
            driver = textwrap.dedent(
                '''
                import asyncio
                import json
                from unittest.mock import patch

                from tui_app import IntermixTUI, MessageComposer
                from textual.widgets import Button

                async def run():
                    stop = asyncio.Event()

                    def request_stop():
                        stop.set()
                        return True

                    async def fake_stream(prompt):
                        yield "token", "A safe partial response. "
                        await stop.wait()
                        yield "generation_stopped", json.dumps({
                            "reason": "user",
                            "detail": "Stopped by the user before completion.",
                            "incomplete": True,
                        })

                    app = IntermixTUI()
                    with (
                        patch("tui_app.stream_inference", new=fake_stream),
                        patch("tui_app.cancel_active_operations", new=request_stop),
                    ):
                        async with app.run_test(size=(48, 32)) as pilot:
                            composer = app.query_one("#input-box", MessageComposer)
                            composer.load_text("Start a long response")
                            composer.cursor_location = composer.document.end
                            composer.focus()
                            await pilot.press("enter")
                            await pilot.pause()
                            button = app.query_one("#voice-last", Button)
                            assert app.busy
                            assert str(button.label) == "STOP", button.label
                            assert button.display and not button.disabled
                            await pilot.click("#voice-last")
                            for _ in range(50):
                                await pilot.pause()
                                if not app.busy:
                                    break
                            assert not app.busy
                            assert not composer.disabled
                            assert not app.voice_generation_complete
                    print("generation-stop-recovery-ok")

                asyncio.run(run())
                '''
            )
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("generation-stop-recovery-ok", result.stdout)

    def test_multiline_composer_preserves_paste_blank_lines_and_submission(self):
        with tempfile.TemporaryDirectory() as temp:
            env = os.environ.copy()
            env["HOME"] = temp
            env["PYTHONPATH"] = os.pathsep.join(
                [str(ENGINE), *[path for path in sys.path if path]]
            )
            driver = textwrap.dedent(
                '''
                import asyncio
                from unittest.mock import patch

                from textual import events
                from textual.containers import Horizontal
                from tui_app import IntermixTUI, MessageComposer

                async def run():
                    captured = []

                    async def fake_stream(prompt):
                        captured.append(prompt)
                        yield "token", "Accepted."

                    app = IntermixTUI()
                    with patch("tui_app.stream_inference", new=fake_stream):
                        async with app.run_test(size=(91, 38)) as pilot:
                            composer = app.query_one("#input-box", MessageComposer)
                            composer.focus()
                            await composer._on_paste(events.Paste("Line one\\n\\nLine three"))
                            await pilot.pause()
                            assert composer.text == "Line one\\n\\nLine three"
                            await pilot.press("shift+enter")
                            composer.insert("Line four", maintain_selection_offset=False)
                            await pilot.pause()
                            expected = "Line one\\n\\nLine three\\nLine four"
                            assert composer.text == expected, repr(composer.text)
                            assert app.query_one("#composer", Horizontal).outer_size.height >= 5
                            await pilot.press("enter")
                            for _ in range(50):
                                await pilot.pause()
                                if captured and not app.busy:
                                    break
                            assert captured == [expected], captured
                            assert composer.text == ""
                            await pilot.pause()
                            assert app.query_one("#composer", Horizontal).outer_size.height == 3
                    print("multiline-composer-submission-ok")

                asyncio.run(run())
                '''
            )
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("multiline-composer-submission-ok", result.stdout)

    def test_multiline_composer_caps_height_and_retains_slash_completion(self):
        with tempfile.TemporaryDirectory() as temp:
            env = os.environ.copy()
            env["HOME"] = temp
            env["PYTHONPATH"] = os.pathsep.join(
                [str(ENGINE), *[path for path in sys.path if path]]
            )
            driver = textwrap.dedent(
                '''
                import asyncio

                from textual.containers import Horizontal
                from tui_app import IntermixTUI, MessageComposer

                async def run():
                    app = IntermixTUI()
                    async with app.run_test(size=(91, 38)) as pilot:
                        composer = app.query_one("#input-box", MessageComposer)
                        composer.focus()
                        composer.load_text("\\n".join(f"Line {number}" for number in range(10)))
                        composer.cursor_location = composer.document.end
                        await pilot.pause()
                        await pilot.pause()
                        assert app.query_one("#composer", Horizontal).outer_size.height == 8

                        composer.load_text("/gen")
                        composer.cursor_location = composer.document.end
                        composer.update_suggestion()
                        await pilot.pause()
                        assert composer.suggestion == "eration status", composer.suggestion
                        await pilot.press("right")
                        assert composer.text == "/generation status", composer.text

                        composer.load_text("One line")
                        await pilot.pause()
                        await pilot.pause()
                        assert app.query_one("#composer", Horizontal).outer_size.height == 3
                    print("multiline-composer-layout-ok")

                asyncio.run(run())
                '''
            )
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("multiline-composer-layout-ok", result.stdout)

    def test_workspace_lens_opens_edits_and_saves(self):
        with tempfile.TemporaryDirectory() as temp:
            env = os.environ.copy()
            env["HOME"] = temp
            env["PYTHONPATH"] = os.pathsep.join(
                [str(ENGINE), *[path for path in sys.path if path]]
            )
            driver = textwrap.dedent(
                '''
                import asyncio
                from tui_app import IntermixTUI
                from textual.containers import Vertical
                from textual.widgets import TextArea
                from workspace_state import WORKSPACE_DIR

                async def run():
                    target = WORKSPACE_DIR / "lens_probe.py"
                    target.write_text('print("BEFORE")\\n', encoding="utf-8")
                    app = IntermixTUI()
                    async with app.run_test(size=(150, 48)) as pilot:
                        await pilot.pause()
                        assert not app.query_one("#workspace-panel", Vertical).display
                        await app.action_toggle_workspace()
                        assert app.query_one("#workspace-panel", Vertical).display
                        app._open_workspace_file(target)
                        editor = app.query_one("#file-editor", TextArea)
                        assert editor.text == 'print("BEFORE")\\n'
                        editor.text = 'print("AFTER")\\n'
                        await app.action_save_workspace_file()
                        assert target.read_text(encoding="utf-8") == 'print("AFTER")\\n'
                        await app.action_toggle_workspace()
                        assert not app.query_one("#workspace-panel", Vertical).display
                    print("workspace-lens-ok")

                asyncio.run(run())
                '''
            )
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("workspace-lens-ok", result.stdout)

    def test_clarity_messages_render_markdown_once_and_fold_long_diagnostics(self):
        with tempfile.TemporaryDirectory() as temp:
            env = os.environ.copy()
            env["HOME"] = temp
            env["PYTHONPATH"] = os.pathsep.join(
                [str(ENGINE), *[path for path in sys.path if path]]
            )
            driver = textwrap.dedent(
                '''
                import asyncio
                from tui_app import (
                    AssistantMessage, IntermixTUI, STREAM_REPAINT_SECONDS,
                    SystemPanel,
                )
                from textual.containers import VerticalScroll
                from textual.widgets import Markdown, Static

                async def run():
                    app = IntermixTUI()
                    async with app.run_test(size=(150, 48)) as pilot:
                        transcript = app.query_one("#transcript", VerticalScroll)
                        message = AssistantMessage("Preparing…", pending=True)
                        await transcript.mount(message)
                        await pilot.pause()
                        message.set_stream_content("## Architecture")
                        stream = message.query_one(".assistant-stream-body", Static)
                        rendered = message.query_one(".assistant-markdown-body", Markdown)
                        assert stream.display and not rendered.display
                        await message.finalize(
                            "## Architecture\\n\\n**Verified** recall with `CONTINUITY_OK`. ✅"
                        )
                        await pilot.pause()
                        assert not stream.display and rendered.display
                        assert "## Architecture" in rendered.source
                        assert "**Verified**" in rendered.source

                        folded = SystemPanel("Runtime", "line\\n" * 20)
                        warning = SystemPanel("Guard", "visible", warning=True)
                        await transcript.mount(folded, warning)
                        await pilot.pause()
                        assert folded.collapsed is True
                        assert warning.collapsed is False
                        assert STREAM_REPAINT_SECONDS >= 0.075
                    print("clarity-presentation-ok")

                asyncio.run(run())
                '''
            )
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("clarity-presentation-ok", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
