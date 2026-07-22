# -*- coding: utf-8 -*-
from __future__ import annotations

import globalPluginHandler
import gui
import wx
import threading
import json
import urllib.request
import os
import tempfile
from contextlib import suppress
from logHandler import log

try:
    import addonHandler
    addonHandler.initTranslation()
except Exception:
    def _(msg: str) -> str:
        return msg

CURRENT_VERSION = "0.2"

class GlobalPlugin(globalPluginHandler.GlobalPlugin):
    def __init__(self) -> None:
        super().__init__()
        self._menu_item: wx.MenuItem | None = None
        try:
            tools_menu = gui.mainFrame.sysTrayIcon.toolsMenu
            self._menu_item = tools_menu.Append(
                wx.ID_ANY,
                _("Edge TTS Voice &Refresh"),
                _("Refresh the Edge TTS voice list from Microsoft servers"),
            )
            gui.mainFrame.sysTrayIcon.Bind(wx.EVT_MENU, self._on_refresh_voices, self._menu_item)
        except Exception:
            log.debug("Edge TTS: Could not add tools menu item.", exc_info=True)

        # Start checking for updates in a background thread
        threading.Thread(target=self._check_for_updates, daemon=True, name="edgeTts.updater").start()

    def terminate(self) -> None:
        if self._menu_item is not None:
            try:
                tools_menu = gui.mainFrame.sysTrayIcon.toolsMenu
                tools_menu.Remove(self._menu_item)
            except Exception:
                pass
            self._menu_item = None

    def _on_refresh_voices(self, event: wx.CommandEvent) -> None:
        def refresh() -> None:
            try:
                from synthDrivers.edgeTts.edge_tts_client import list_voices
                voices = list_voices(force_refresh=True)
                wx.CallAfter(
                    gui.messageBox,
                    _("Successfully refreshed {count} Edge TTS voices.").format(count=len(voices)),
                    _("Edge TTS For NVDA"),
                    wx.OK | wx.ICON_INFORMATION,
                    gui.mainFrame,
                )
            except Exception as exc:
                wx.CallAfter(
                    gui.messageBox,
                    _("Failed to refresh voices: {error}").format(error=str(exc)),
                    _("Edge TTS For NVDA"),
                    wx.OK | wx.ICON_ERROR,
                    gui.mainFrame,
                )

        threading.Thread(target=refresh, daemon=True).start()

    def _check_for_updates(self) -> None:
        try:
            # Check latest release from user's GitHub repository
            url = "https://api.github.com/repos/athee06/edgeTtsForNvda/releases/latest"
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "NVDA-Addon-Updater/1.0"}
            )
            with urllib.request.urlopen(req, timeout=10) as response:
                data = json.loads(response.read().decode('utf-8'))
                latest_version_str = data.get("tag_name", "").lstrip("v")
                if not latest_version_str:
                    return
                
                try:
                    latest_ver = float(latest_version_str)
                    current_ver = float(CURRENT_VERSION)
                except ValueError:
                    latest_ver = latest_version_str
                    current_ver = CURRENT_VERSION
                    
                if latest_ver > current_ver:
                    assets = data.get("assets", [])
                    download_url = ""
                    for asset in assets:
                        if asset.get("name", "").endswith(".nvda-addon"):
                            download_url = asset.get("browser_download_url", "")
                            break
                    
                    if download_url:
                        wx.CallAfter(self._prompt_update, latest_version_str, download_url)
        except Exception:
            log.debug("Edge TTS: Background update check failed.", exc_info=True)

    def _prompt_update(self, latest_version: str, download_url: str) -> None:
        confirm = gui.messageBox(
            _("A new version ({version}) of Edge TTS For NVDA is available. Would you like to download and install it now?").format(version=latest_version),
            _("Edge TTS Update Available"),
            wx.YES_NO | wx.ICON_QUESTION,
            gui.mainFrame
        )
        if confirm == wx.YES:
            threading.Thread(target=self._download_and_install_addon, args=(download_url,), daemon=True).start()

    def _download_and_install_addon(self, url: str) -> None:
        progress = wx.CallAfter(self._create_progress_dialog)
        
        temp_dir = tempfile.gettempdir()
        addon_path = os.path.join(temp_dir, "edgeTtsForNvda_update.nvda-addon")
        
        if os.path.exists(addon_path):
            with suppress(Exception):
                os.remove(addon_path)
                
        def report_hook(count, block_size, total_size):
            if total_size > 0:
                percent = min(100, int(count * block_size * 100 / total_size))
                wx.CallAfter(self._update_progress, progress, percent)
                
        try:
            urllib.request.urlretrieve(url, addon_path, reporthook=report_hook)
            wx.CallAfter(self._destroy_progress_dialog, progress)
            # Run the downloaded file using default handler (initiates NVDA add-on install dialog)
            os.startfile(addon_path)
        except Exception as e:
            wx.CallAfter(self._destroy_progress_dialog, progress)
            wx.CallAfter(
                gui.messageBox,
                _("Failed to download update:\n{error}").format(error=str(e)),
                _("Update Error"),
                wx.OK | wx.ICON_ERROR,
                gui.mainFrame
            )

    def _create_progress_dialog(self) -> wx.ProgressDialog:
        return wx.ProgressDialog(
            _("Downloading Update"),
            _("Downloading Edge TTS Update, please wait..."),
            maximum=100,
            parent=gui.mainFrame,
            style=wx.PD_APP_MODAL | wx.PD_AUTO_HIDE
        )

    def _update_progress(self, dialog: wx.ProgressDialog, percent: int) -> None:
        if dialog:
            dialog.Update(percent)

    def _destroy_progress_dialog(self, dialog: wx.ProgressDialog) -> None:
        if dialog:
            dialog.Destroy()
