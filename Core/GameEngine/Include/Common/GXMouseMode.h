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


// GeneralsX @feature Android port 05/10/2026 The launcher's "Mouse and touchpad" switch (issue #39).
//
// Off by default: the game is played by touch, read natively by the touch layer
// (SDL3GameEngine.cpp, TouchInput.cpp), and there is no pointer. Turned on, it drops an empty file
// named mouse_mode into the app's own files directory (SetupActivity.MOUSE_MODE_MARKER), and the
// game has a pointer instead: a connected mouse drives it exactly as on a PC (SDL3Mouse feeds the
// message stream), and a finger moves it like a laptop touchpad (SDL3GameEngine.cpp,
// handleTouchpadEvent), with the cursor drawn by the game. Read the same way as GXLogging.h, once
// per process. Always FALSE off Android.
#pragma once

#if defined(__ANDROID__)
#include <cstdio>
#include <ctime>
#include <unistd.h>
#endif

// Milliseconds on a monotonic clock, for the re-read throttle below.
inline long long GXMouseModeNowMs()
{
#if defined(__ANDROID__)
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
#else
	return 0;
#endif
}

// GeneralsX @feature ZH Commander 11/10/2026 A percent the launcher keeps in a small file in the
// app's files directory (SetupActivity.writePercentFile), or `fallback` when the file is missing or
// out of [lo, hi]. Read again every two seconds at most: the launcher's settings open over a running
// game, and a slider moved there applies when the player comes back, with no restart.
inline int GXReadPercentFile(const char *name, int lo, int hi, int fallback)
{
#if defined(__ANDROID__)
	char path[256];
	const int userId = (int)(getuid() / 100000);
	snprintf(path, sizeof(path), "/data/user/%d/com.housamkak.zhcommander/files/%s", userId, name);
	int percent = fallback;
	FILE *fp = fopen(path, "r");
	if (fp != nullptr)
	{
		int value = 0;
		if (fscanf(fp, "%d", &value) == 1 && value >= lo && value <= hi)
			percent = value;
		fclose(fp);
	}
	return percent;
#else
	return fallback;
#endif
}

// The cursor size the launcher's slider chose, in percent (50-200). 100 when unset.
//
// GeneralsX @bugfix ZH Commander 11/10/2026 The marker is looked for under this app's own package
// (com.housamkak.zhcommander). The upstream path (com.generalsx.zerohour) never exists here, so the
// switch did nothing at all: the launcher wrote the marker and the game never saw it.
// GeneralsX @feature ZH Commander 11/10/2026 Kept in its own file (cursor_size) since a real mouse
// has a cursor with the switch off too; the marker's content, where the upstream launcher kept it,
// is the fallback.
inline int GXMouseCursorPercent()
{
#if defined(__ANDROID__)
	static int percent = 100;
	static long long readAt = -1;
	const long long now = GXMouseModeNowMs();
	if (readAt < 0 || now - readAt >= 2000)
	{
		readAt = now;
		percent = GXReadPercentFile("cursor_size", 50, 200, GXReadPercentFile("mouse_mode", 50, 200, 100));
	}
	return percent;
#else
	return 100;
#endif
}

// GeneralsX @feature ZH Commander 11/10/2026 How far the touchpad cursor moves for a finger's
// movement, in percent of the original speed (50-300). 100 when unset. A real mouse's speed is
// Android's own pointer speed setting: its positions arrive already accelerated.
inline int GXTouchpadSpeedPercent()
{
#if defined(__ANDROID__)
	static int percent = 100;
	static long long readAt = -1;
	const long long now = GXMouseModeNowMs();
	if (readAt < 0 || now - readAt >= 2000)
	{
		readAt = now;
		percent = GXReadPercentFile("touchpad_speed", 50, 300, 100);
	}
	return percent;
#else
	return 100;
#endif
}

inline bool GXMouseModeEnabled()
{
#if defined(__ANDROID__)
	static int state = -1;
	if (state < 0)
	{
		char path[256];
		const int userId = (int)(getuid() / 100000);
		snprintf(path, sizeof(path), "/data/user/%d/com.housamkak.zhcommander/files/mouse_mode", userId);
		state = (access(path, F_OK) == 0) ? 1 : 0;
	}
	return state == 1;
#else
	return false;
#endif
}

// GeneralsX @feature ZH Commander 11/10/2026 A real mouse plays as on a PC with no switch at all.
//
// Player feedback: with a Bluetooth mouse the game still played like a touchscreen, without right
// click, wheel or box selection. A mouse is now taken whenever it is used: SDL3GameEngine.cpp sets
// this on any event from a real mouse (not the touchpad's, not one SDL made from a finger) and
// clears it when a finger touches the screen, so whichever the player used last drives the game.
// The launcher switch now matters only for the touchpad (a finger moving a cursor).
inline bool &GXRealMouseActiveFlag()
{
	static bool active = false;
	return active;
}

inline bool GXRealMouseActive()
{
	return GXRealMouseActiveFlag();
}

inline void GXSetRealMouseActive(bool active)
{
	GXRealMouseActiveFlag() = active;
}

// True while a pointer drives the game: the touchpad mode, or a real mouse used since the last
// touch. What a finger cannot do (hover, rest at a screen edge, aim without pressing) is then the
// pointer's to do, as on a PC.
inline bool GXPointerActive()
{
	return GXMouseModeEnabled() || GXRealMouseActive();
}
