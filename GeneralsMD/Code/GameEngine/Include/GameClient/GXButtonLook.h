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

// GeneralsX @feature ZH Commander 09/10/2026 A button created at runtime that looks like one the
// layout defines. gogoGadgetPushButton() gives a new button the window manager's default look (a
// flat coloured box); this copies a layout button's art, colours and font (the font as the layout
// resolved it, so already scaled for the screen) and its draw function, so the new one sits among
// the retail buttons as one of them.
#pragma once

#include "GameClient/GameWindow.h"
#include "GameClient/WinInstanceData.h"

inline void GXCopyButtonLook( GameWindow *to, GameWindow *from )
{
	if( to == nullptr || from == nullptr )
		return;
	for( Int i = 0; i < MAX_DRAW_DATA; ++i )
	{
		to->winSetEnabledImage( i, from->winGetEnabledImage( i ) );
		to->winSetEnabledColor( i, from->winGetEnabledColor( i ) );
		to->winSetEnabledBorderColor( i, from->winGetEnabledBorderColor( i ) );
		to->winSetDisabledImage( i, from->winGetDisabledImage( i ) );
		to->winSetDisabledColor( i, from->winGetDisabledColor( i ) );
		to->winSetDisabledBorderColor( i, from->winGetDisabledBorderColor( i ) );
		to->winSetHiliteImage( i, from->winGetHiliteImage( i ) );
		to->winSetHiliteColor( i, from->winGetHiliteColor( i ) );
		to->winSetHiliteBorderColor( i, from->winGetHiliteBorderColor( i ) );
	}
	to->winSetEnabledTextColors( from->winGetEnabledTextColor(), from->winGetEnabledTextBorderColor() );
	to->winSetDisabledTextColors( from->winGetDisabledTextColor(), from->winGetDisabledTextBorderColor() );
	to->winSetHiliteTextColors( from->winGetHiliteTextColor(), from->winGetHiliteTextBorderColor() );
	to->winSetFont( from->winGetFont() );
	if( BitIsSet( from->winGetStatus(), WIN_STATUS_IMAGE ) )
		to->winSetStatus( WIN_STATUS_IMAGE );
	to->winSetDrawFunc( from->winGetDrawFunc() );
}
