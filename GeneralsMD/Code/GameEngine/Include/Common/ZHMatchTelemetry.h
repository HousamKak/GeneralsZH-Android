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

// GeneralsX @feature ZH Commander 10/10/2026 Anonymous match statistics for the app's usage
// monitor: match_start and match_end (mode, map file name, side, players, hardest AI, result,
// length, frame rate), sent through ZHCommander::hooks().event. Only reads game state; no names,
// chat or account details. The Telemetry switch is enforced by the app around the engine.
#pragma once

namespace ZHMatchTelemetry
{
	// GameLogic::tryStartNewGame, once the map has loaded. A loaded save does not start a match
	// (it only closes one still open).
	void matchStarted(bool loadingSaveGame);

	// GameEngine::execute, once per frame after the frame pacer: frame timing while a match runs,
	// the result once the game is decided, and match_end once the game has left the match.
	void frame();
}
