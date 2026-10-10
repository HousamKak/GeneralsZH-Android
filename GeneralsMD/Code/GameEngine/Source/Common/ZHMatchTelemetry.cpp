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

// GeneralsX @feature ZH Commander 10/10/2026 Match statistics (see ZHMatchTelemetry.h).
//
// match_start is sent when a real match has loaded (not the shell map, not a loaded save).
// While it runs, each frame's length (from the frame pacer, so it includes the limiter's wait)
// goes into a count, a sum, a count of frames over 50 ms and a 64-bucket histogram (2 ms each,
// the last one open-ended) for the 95th percentile; frames over a second are pauses (the app in
// the background) and are skipped. The result is taken from the victory conditions as soon as
// the game is decided, while that state is still valid, and match_end goes out once the game
// is no longer in the match (score screen closed, quit, or the next mission loading).

#include "PreRTS.h"	// This must go first in EVERY cpp file in the GameEngine

#include "Common/FramePacer.h"
#include "Common/GlobalData.h"
#include "Common/Player.h"
#include "Common/PlayerList.h"
#include "Common/PlayerTemplate.h"
#include "Common/ZHCommander.h"
#include "Common/ZHMatchTelemetry.h"
#include "GameClient/CampaignManager.h"
#include "GameLogic/GameLogic.h"
#include "GameLogic/ScriptEngine.h"
#include "GameLogic/VictoryConditions.h"
#include "GameNetwork/GameInfo.h"

#include <cstring>

namespace
{
	const int BUCKETS = 64;
	const double BUCKET_MS = 2.0;
	const double SLOW_MS = 50.0;
	const double PAUSE_MS = 1000.0;

	struct Match
	{
		bool active;
		char mode[16];
		char map[64];
		char side[64];
		char aiMax[8];
		int players;
		const char *result;	// win / loss once decided, else null
		unsigned int lastLogicFrame;
		unsigned int frames;
		unsigned int slowFrames;
		double totalMs;
		unsigned int hist[BUCKETS];
	};

	Match s_match = {};

	void copy(char *dst, size_t size, const char *src)
	{
		std::strncpy(dst, src != nullptr ? src : "", size - 1);
		dst[size - 1] = '\0';
	}

	const char *modeName()
	{
		switch (TheGameLogic->getGameMode())
		{
			case GAME_SKIRMISH: return "skirmish";
			case GAME_LAN: return "lan";
			case GAME_INTERNET: return "online";
			case GAME_REPLAY: return "replay";
			case GAME_SINGLE_PLAYER:
			{
				Campaign *campaign = TheCampaignManager != nullptr ? TheCampaignManager->getCurrentCampaign() : nullptr;
				return campaign != nullptr && campaign->isChallengeCampaign() ? "challenge" : "campaign";
			}
			default: return nullptr;
		}
	}

	// "Maps\Tournament Desert\Tournament Desert.map" -> "Tournament Desert": the file name only.
	void mapBaseName(char *dst, size_t size)
	{
		const char *path = TheGlobalData->m_mapName.str();
		const char *slash = std::strrchr(path, '\\');
		const char *fwd = std::strrchr(path, '/');
		if (fwd != nullptr && (slash == nullptr || fwd > slash)) slash = fwd;
		copy(dst, size, slash != nullptr ? slash + 1 : path);
		char *dot = std::strrchr(dst, '.');
		if (dot != nullptr) *dot = '\0';
	}

	const char *difficultyName(int level)
	{
		return level == 3 ? "hard" : level == 2 ? "medium" : level == 1 ? "easy" : "none";
	}

	ZHCommander::Event describe(const char *name)
	{
		ZHCommander::Event e(name);
		e.add("mode", s_match.mode).add("map", s_match.map).add("side", s_match.side).add("ai_max", s_match.aiMax);
		if (s_match.players > 0) e.add("players", s_match.players);
		return e;
	}

	void endMatch()
	{
		if (!s_match.active) return;
		s_match.active = false;
		ZHCommander::Event e = describe("match_end");
		const bool replay = std::strcmp(s_match.mode, "replay") == 0;
		e.add("result", replay ? "unknown" : s_match.result != nullptr ? s_match.result : "quit");
		e.add("duration_s", (int)(s_match.lastLogicFrame / LOGICFRAMES_PER_SECOND));
		if (s_match.frames > 0 && s_match.totalMs > 0.0)
		{
			e.add("avg_fps", 1000.0 * s_match.frames / s_match.totalMs);
			e.add("slow_frame_pct", 100.0 * s_match.slowFrames / s_match.frames);
			const unsigned int target = (unsigned int)(s_match.frames * 0.95);
			unsigned int seen = 0;
			int bucket = 0;
			for (; bucket < BUCKETS - 1; ++bucket)
			{
				seen += s_match.hist[bucket];
				if (seen >= target) break;
			}
			e.add("p95_frame_ms", (int)((bucket + 1) * BUCKET_MS));
		}
		e.send();
	}
}

void ZHMatchTelemetry::matchStarted(bool loadingSaveGame)
{
	if (ZHCommander::hooks().event == nullptr || TheGameLogic == nullptr) return;
	endMatch();
	const char *mode = modeName();
	if (loadingSaveGame || mode == nullptr) return;

	std::memset(&s_match, 0, sizeof(s_match));
	copy(s_match.mode, sizeof(s_match.mode), mode);
	mapBaseName(s_match.map, sizeof(s_match.map));

	Player *local = ThePlayerList != nullptr ? ThePlayerList->getLocalPlayer() : nullptr;
	const PlayerTemplate *side = local != nullptr ? local->getPlayerTemplate() : nullptr;
	copy(s_match.side, sizeof(s_match.side), side != nullptr ? side->getName().str() : "observer");

	// Players (humans and AIs, not observers) and the hardest AI, from the slots; a campaign
	// mission has no slots and takes the campaign difficulty.
	int hardest = 0;
	if (TheGameInfo != nullptr)
	{
		for (Int i = 0; i < MAX_SLOTS; ++i)
		{
			const GameSlot *slot = TheGameInfo->getConstSlot(i);
			if (slot == nullptr || !slot->isOccupied() || slot->getPlayerTemplate() == PLAYERTEMPLATE_OBSERVER) continue;
			++s_match.players;
			const SlotState st = slot->getState();
			const int level = st == SLOT_BRUTAL_AI ? 3 : st == SLOT_MED_AI ? 2 : st == SLOT_EASY_AI ? 1 : 0;
			if (level > hardest) hardest = level;
		}
	}
	else if (TheScriptEngine != nullptr)
	{
		const GameDifficulty d = TheScriptEngine->getGlobalDifficulty();
		hardest = d == DIFFICULTY_HARD ? 3 : d == DIFFICULTY_NORMAL ? 2 : 1;
	}
	copy(s_match.aiMax, sizeof(s_match.aiMax), difficultyName(hardest));
	s_match.active = true;
	describe("match_start").send();
}

void ZHMatchTelemetry::frame()
{
	if (!s_match.active || TheGameLogic == nullptr) return;
	if (!TheGameLogic->isInGame() || TheGameLogic->isInShellGame() || TheGameLogic->isLoadingMap())
	{
		endMatch();
		return;
	}
	s_match.lastLogicFrame = TheGameLogic->getFrame();

	if (s_match.result == nullptr && TheVictoryConditions != nullptr && !TheVictoryConditions->amIObserver())
	{
		const bool campaign = std::strcmp(s_match.mode, "campaign") == 0 || std::strcmp(s_match.mode, "challenge") == 0;
		if (TheVictoryConditions->isLocalAlliedVictory() || (campaign && TheCampaignManager != nullptr && TheCampaignManager->isVictorious()))
			s_match.result = "win";
		else if (TheVictoryConditions->isLocalAlliedDefeat() || TheVictoryConditions->isLocalDefeat())
			s_match.result = "loss";
	}

	if (TheFramePacer == nullptr) return;
	const double ms = TheFramePacer->getUpdateTime() * 1000.0;
	if (ms <= 0.0 || ms > PAUSE_MS) return;
	++s_match.frames;
	s_match.totalMs += ms;
	if (ms > SLOW_MS) ++s_match.slowFrames;
	int bucket = (int)(ms / BUCKET_MS);
	if (bucket >= BUCKETS) bucket = BUCKETS - 1;
	++s_match.hist[bucket];
}
