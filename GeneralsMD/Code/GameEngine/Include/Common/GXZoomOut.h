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

// GeneralsX @feature ZH Commander 11/10/2026 How far the camera may zoom out (owner's request:
// "I want to be able to zoom out more").
//
// The limit is GlobalData::m_maxCameraHeight (GameData.ini, 310 retail; SagePatch.ini raises it),
// which W3DView::setCameraHeightAboveGroundLimitsToDefault scales for the screen's aspect ratio.
// The ZH COMMANDER menu keeps a higher limit as GXZoomOut in Options.ini (0: the game's own), and
// this applies it whenever a match starts (GameLogic::startNewGame) or the menu changes it. A
// multiplayer match keeps the game's own limit: zooming out further than the other players, who
// are on PCs with the stock limit, would be an advantage. The camera is the client's alone, so no
// value here can desync a match or a replay.
#pragma once

#include "Common/GlobalData.h"
#include "Common/OptionPreferences.h"

// The limit the game data set, remembered the first time it is read so a higher choice can be
// taken back.
inline Real &GXZoomOutBaseHeight()
{
	static Real base = -1.0f;
	if (base < 0.0f && TheGlobalData != nullptr)
		base = TheGlobalData->m_maxCameraHeight;
	return base;
}

// The player's choice from Options.ini, 0 for the game's own limit.
inline Int GXZoomOutChoice()
{
	OptionPreferences pref;
	return pref.getInt("GXZoomOut", 0);
}

// Sets the maximum camera height for the match being started (or the one being played).
inline void GXApplyZoomOut(Bool multiplayer)
{
	if (TheWritableGlobalData == nullptr)
		return;
	const Real base = GXZoomOutBaseHeight();
	Real height = base;
	if (!multiplayer)
	{
		const Int choice = GXZoomOutChoice();
		if ((Real)choice > base)
			height = (Real)choice;
	}
	TheWritableGlobalData->m_maxCameraHeight = height;
}
