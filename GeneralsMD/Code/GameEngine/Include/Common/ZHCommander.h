/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature ZH Commander 09/10/2026 What the game's own menus ask of the app around it.
//
// The app version, an update waiting to be installed, the support report and the settings that
// live outside Options.ini belong to the Android or iOS shell, not to the engine. The shell's
// startup code (SDL3Main.cpp: AndroidBridge.cpp on Android, IOSGate.mm on iOS) fills these hooks
// before GameMain() runs; the menus (MainMenu.cpp, CommanderMenu.cpp) only call them. A hook
// left null means the platform has no such thing, and the menus leave that entry out.
// Plain C++ on purpose: IOSGate.mm includes it without the engine's precompiled header.
#pragma once

namespace ZHCommander
{
	struct Hooks
	{
		// "1.5.7". Never null once installed.
		const char *(*appVersion)();

		// A newer app release, its version written to version. mandatory: the shell will not
		// start the game until it is installed.
		bool (*appUpdateOffer)(char *version, int size, bool *mandatory);
		void (*startAppUpdate)();

		// Newer game data than what is installed, its version written to version.
		bool (*dataUpdateOffer)(char *version, int size);
		// Leaves the game for the download (restarting the app where it can).
		void (*startDataUpdate)();

		// Sends the logs and a device summary to the developer's server and tells the player the
		// reference it got back (the share sheet only when the upload fails).
		void (*shareSupportReport)();

		// The shell's own settings screen (drivers, GeneralsOnline account, language packs).
		void (*openMoreSettings)();

		// Settings the shell keeps itself, read before the engine starts: "sim_hz" ("30"/"60")
		// and "render_backend" ("gles"/"gles_angle"/"vulkan").
		bool (*getSetting)(const char *key, char *value, int size);
		void (*setSetting)(const char *key, const char *value);

		// Closes the game and starts it again, through the shell's start-up checks.
		void (*restart)();

		// GeneralsOnline account: the name this device is signed in as (false when not signed in),
		// the sign-in (opens the browser, returns to the game when done) and sign-out.
		bool (*onlineAccount)(char *name, int size);
		void (*onlineSignIn)();
		void (*onlineSignOut)();

		// Release notes (UTF-8, in the game's text language): this version's, once after an update
		// ("What's new"), and an offered version's, for the update dialog. Body lines start "• ".
		bool (*whatsNew)(char *title, int titleSize, char *body, int bodySize);
		bool (*releaseNotes)(const char *version, char *title, int titleSize, char *body, int bodySize);

		// Something worth counting happened ("engine_boot"), for the app's usage monitor. Client
		// side only: never called from game logic, which must stay deterministic.
		void (*event)(const char *name);
	};

	inline Hooks &hooks()
	{
		static Hooks s_hooks = {};
		return s_hooks;
	}

	inline const char *appVersion()
	{
		return hooks().appVersion ? hooks().appVersion() : nullptr;
	}
}
