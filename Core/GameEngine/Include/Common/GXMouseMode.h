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
#include <unistd.h>
#endif

// The cursor size the launcher's slider chose, in percent (50-200), kept as the marker's content.
// 100 when the marker is empty or unreadable.
//
// GeneralsX @bugfix ZH Commander 11/10/2026 The marker is looked for under this app's own package
// (com.housamkak.zhcommander). The upstream path (com.generalsx.zerohour) never exists here, so the
// switch did nothing at all: the launcher wrote the marker and the game never saw it.
inline int GXMouseCursorPercent()
{
#if defined(__ANDROID__)
	static int percent = -1;
	if (percent < 0)
	{
		percent = 100;
		char path[256];
		const int userId = (int)(getuid() / 100000);
		snprintf(path, sizeof(path), "/data/user/%d/com.housamkak.zhcommander/files/mouse_mode", userId);
		FILE *fp = fopen(path, "r");
		if (fp != nullptr)
		{
			int value = 0;
			if (fscanf(fp, "%d", &value) == 1 && value >= 50 && value <= 200)
				percent = value;
			fclose(fp);
		}
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
