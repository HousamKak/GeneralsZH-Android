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

/*
** SDL3Mouse.cpp
**
** SDL3-based mouse implementation for Linux builds.
**
** TheSuperHackers @feature CnC_Generals_Linux 10/02/2026 Bender
** Replaces Win32Mouse/Win32DIMouse with SDL3 mouse APIs for Linux.
*/

#ifndef _WIN32

// GeneralsX @bugfix BenderAI 13/02/2026 Fix include path (fighter19 pattern)
#include "SDL3Device/GameClient/SDL3Mouse.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>

// GeneralsX @bugfix felipebraz 18/02/2026 Include GameLogic for frame tracking
#include "GameLogic/GameLogic.h"
// GeneralsX @bugfix felipebraz 20/02/2026 Include Display to get internal resolution for coordinate scaling
#include "GameClient/Display.h"
// GeneralsX @bugfix BenderAI 22/02/2026 Add SDL3_image for cursor loading
// SDL3_image now finds system libpng via pkg-config (CMAKE_PREFIX_PATH reordered in cmake/sdl3.cmake)
#include <SDL3_image/SDL_image.h>
// GeneralsX @bugfix BenderAI 22/02/2026 Add array header for AnimatedCursor
#include <array>
#if (defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE) || defined(__ANDROID__)
// GeneralsX @feature Android port 05/10/2026 The touchpad pointer is drawn by the game (draw()).
#include "Common/GXMouseMode.h"
#include "GameClient/InGameUI.h"
#include <math.h>
#include "GameClient/Image.h"
#include "WW3D2/surfaceclass.h"
#include "WW3D2/texture.h"
#endif
// GeneralsX @bugfix BenderAI 22/02/2026 Add file system includes for cursor loading
#include "Common/Debug.h"
#include "Common/file.h"
#include "Common/FileSystem.h"

/**
 * AnimatedCursor - Helper struct for cursor animation (fighter19 pattern)
 * Holds the frames of an animated cursor with frame timing info
 */
struct AnimatedCursor {
	SDL_Cursor* m_cursor = nullptr;
	std::array<SDL_Surface*, MAX_2D_CURSOR_ANIM_FRAMES> m_frameSurfaces;
	int m_frameCount = 0;
	int m_frameRate = 0; // the time a frame is displayed in 1/60th of a second
	// GeneralsX @feature Android port 05/10/2026 Hot spot of the frames, and the frames as images
	// the game can draw (SDL3Mouse::draw(), touchpad pointer). Made on first use.
	int m_hotX = 0;
	int m_hotY = 0;
#if (defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE) || defined(__ANDROID__)
	std::array<Image*, MAX_2D_CURSOR_ANIM_FRAMES> m_gxImages = {};
	std::array<TextureClass*, MAX_2D_CURSOR_ANIM_FRAMES> m_gxTextures = {};
#endif

	~AnimatedCursor()
	{
#if (defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE) || defined(__ANDROID__)
		for (int i = 0; i < MAX_2D_CURSOR_ANIM_FRAMES; i++)
		{
			if (m_gxImages[i])
				deleteInstance(m_gxImages[i]);
			REF_PTR_RELEASE(m_gxTextures[i]);
		}
#endif
		if (m_cursor)
		{
			SDL_DestroyCursor(m_cursor);
			m_cursor = nullptr;
		}

		for (int i = 0; i < MAX_2D_CURSOR_ANIM_FRAMES; i++)
		{
			if (m_frameSurfaces[i])
			{
				SDL_DestroySurface(m_frameSurfaces[i]);
				m_frameSurfaces[i] = nullptr;
			}
		}
	}
};

// Global cursor resources array - fighter19 pattern
// GeneralsX @bugfix BenderAI 22/02/2026 Global cursor resource cache
AnimatedCursor* cursorResources[Mouse::NUM_MOUSE_CURSORS][MAX_2D_CURSOR_DIRECTIONS];

// RIFF/ANI parsing helpers (fighter19 pattern)
// GeneralsX @bugfix BenderAI 22/02/2026 RIFF format parsing for cursor loading
typedef std::array<char, 4> FourCC;
constexpr FourCC riff_id = {'R', 'I', 'F', 'F'};
constexpr FourCC acon_id = {'A', 'C', 'O', 'N'};
constexpr FourCC anih_id = {'a', 'n', 'i', 'h'};
constexpr FourCC fram_id = {'f', 'r', 'a', 'm'};
constexpr FourCC icon_id = {'i', 'c', 'o', 'n'};
constexpr FourCC seq_id = {'s', 'e', 'q', ' '};
constexpr FourCC rate_id = {'r', 'a', 't', 'e'};
constexpr FourCC list_id = {'L', 'I', 'S', 'T'};

struct ANIHeader
{
	uint32_t size; // Should be 32 bytes (all fields below)
	uint32_t frames;
	uint32_t steps;
	uint32_t width;
	uint32_t height;
	uint32_t bitsPerPixel;
	uint32_t planes;
	uint32_t displayRate;
	uint32_t flags;
};

struct RIFFChunk
{
	FourCC id; // Should be 'RIFF' for the first 4 bytes
	uint32_t size; // Size of the file minus 8 bytes
	FourCC type; // Should be 'ACON' in the first chunk
};

static_assert(sizeof(ANIHeader) == 36, "ANIHeader size is not 36 bytes");

static RIFFChunk* getNextChunk(RIFFChunk* chunk)
{
	return (RIFFChunk*)((char*)chunk + sizeof(RIFFChunk) + chunk->size - 4);
}

static void* getChunkData(RIFFChunk* chunk)
{
	// Type is also part of the chunk, but also data, remove it from the size
	return (char*)chunk + sizeof(RIFFChunk) - 4;
}

/**
 * Constructor - Initialize SDL3Mouse with window handle
 * @param window SDL3 window handle (required for mouse capture)
 */
SDL3Mouse::SDL3Mouse(SDL_Window* window)
	: Mouse(),
	  m_Window(window),
	  m_IsCaptured(false),
	  m_IsVisible(true),
	  m_LostFocus(false),           // GeneralsX @bugfix felipebraz 18/02/2026 Initialize focus state
	  m_nextFreeIndex(0),   // Fighter19 pattern: write position for new events
	  m_nextGetIndex(0),    // Fighter19 pattern: read position for events
	  m_LeftButtonDownTime(0),
	  m_RightButtonDownTime(0),
	  m_MiddleButtonDownTime(0),
	  m_LastFrameNumber(0),  // GeneralsX @bugfix felipebraz 18/02/2026 Initialize frame tracking
	  m_directionFrame(0),    // GeneralsX @bugfix BenderAI 22/02/2026 Initialize cursor direction frame
	  m_touchpadPointer(FALSE)
{
	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "DEBUG: SDL3Mouse::SDL3Mouse() created\n");

	// Initialize event buffer with SDL_EVENT_FIRST sentinel (means "empty" slot)
	// GeneralsX @refactor felipebraz 16/02/2026 Fighter19 pattern
	memset(m_eventBuffer, 0, sizeof(m_eventBuffer));

	m_LeftButtonDownPos.x = 0;
	m_LeftButtonDownPos.y = 0;
	m_RightButtonDownPos.x = 0;
	m_RightButtonDownPos.y = 0;
	m_MiddleButtonDownPos.x = 0;
	m_MiddleButtonDownPos.y = 0;
}

/**
 * Destructor
 */
SDL3Mouse::~SDL3Mouse(void)
{
	releaseCapture();
	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "DEBUG: SDL3Mouse::~SDL3Mouse() destroyed\n");
}

/**
 * Load cursor from ANI file (fighter19 pattern with RIFF parsing)
 * GeneralsX @bugfix BenderAI 22/02/2026 Port fighter19 cursor loading
 */
AnimatedCursor* SDL3Mouse::loadCursorFromFile(const char* filepath)
{
	File* file = TheFileSystem->openFile(filepath, File::READ | File::BINARY);
	if (!file)
	{
		DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Failed to open ANI cursor [%s]", filepath));
		return NULL;
	}

	// Read entire file and close it
	Int size  = file->size();
	char* file_buffer = file->readEntireAndClose();

	if (!file_buffer)
	{
		DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Failed to read ANI cursor [%s]", filepath));
		file->close();
		return NULL;
	}

	RIFFChunk *riff_header = (RIFFChunk*)file_buffer;
	if (riff_header->id != riff_id)
	{
		DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Not a RIFF file"));
		delete[] file_buffer;
		return NULL;
	}

	if(riff_header->type != acon_id) {
		DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Not an animated cursor file"));
		delete[] file_buffer;
		return NULL;
	}

	DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: loading %s", filepath));
	AnimatedCursor* cursor = new AnimatedCursor();

	RIFFChunk* chunk = (RIFFChunk*)((char*)file_buffer + sizeof(RIFFChunk));

	while (chunk != NULL && (char *)chunk < file_buffer + size)
	{
		if (chunk->id == anih_id)
		{
			if (chunk->size != sizeof(ANIHeader))
			{
				DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Invalid ANI header size"));
				delete cursor;
				delete[] file_buffer;
				return NULL;
			}

			ANIHeader *ani_header = (ANIHeader*)getChunkData(chunk);

			cursor->m_frameCount = ani_header->frames;
			cursor->m_frameRate = ani_header->displayRate;
		}
		else if (chunk->id == list_id && chunk->type == fram_id)
		{
			int frame_index = 0;
			int hot_spot_x = 0;
			int hot_spot_y = 0;
			#ifdef __linux__
			bool has_hotspot = false;
			#endif

			RIFFChunk *frame = (RIFFChunk*)((char *)chunk + sizeof(RIFFChunk));
			while (frame != NULL && (char *)frame < file_buffer + size)
			{
				if (frame->id == icon_id)
				{
					const void *frame_buffer = getChunkData(frame);
					SDL_IOStream *io_stream = SDL_IOFromConstMem(frame_buffer, frame->size);
					SDL_Surface *surface = cursor->m_frameSurfaces[frame_index] = IMG_LoadTyped_IO(io_stream, true, "ico");

					if (!surface)
					{
						DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Failed to load frame"));
						delete cursor;
						delete[] file_buffer;
						return NULL;
					}

					// Allow specifying the hot spot via properties on the surface
					SDL_PropertiesID props = SDL_GetSurfaceProperties(surface);
					#ifdef __linux__
					if (!has_hotspot)
					{
						hot_spot_x = (int)SDL_GetNumberProperty(props, SDL_PROP_SURFACE_HOTSPOT_X_NUMBER, 0);
						hot_spot_y = (int)SDL_GetNumberProperty(props, SDL_PROP_SURFACE_HOTSPOT_Y_NUMBER, 0);
						has_hotspot = true;
					}
					#else
					hot_spot_x = (int)SDL_GetNumberProperty(props, SDL_PROP_SURFACE_HOTSPOT_X_NUMBER, 0);
					hot_spot_y = (int)SDL_GetNumberProperty(props, SDL_PROP_SURFACE_HOTSPOT_Y_NUMBER, 0);
					#endif

					frame_index++;
				}

				if (frame_index >= MAX_2D_CURSOR_ANIM_FRAMES)
				{
					DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Too many frames"));
					break;
				}

				frame = getNextChunk(frame);
			}

			if (frame_index > 0)
			{
				cursor->m_frameCount = frame_index;
				const int clamped_rate = (cursor->m_frameRate > 0) ? cursor->m_frameRate : 4;
				const Uint32 frame_duration_ms = (Uint32)((clamped_rate * 1000) / 60);
				SDL_CursorFrameInfo frame_infos[MAX_2D_CURSOR_ANIM_FRAMES];
				#ifdef __linux__
				SDL_Surface *first_surface = cursor->m_frameSurfaces[0];
				#endif

				for (int i = 0; i < frame_index; ++i)
				{
					frame_infos[i].surface = cursor->m_frameSurfaces[i];
					frame_infos[i].duration = frame_duration_ms;
				}

				#ifdef __linux__
				if (first_surface)
				{
					if (hot_spot_x < 0) hot_spot_x = 0;
					if (hot_spot_y < 0) hot_spot_y = 0;
					if (hot_spot_x >= first_surface->w) hot_spot_x = first_surface->w - 1;
					if (hot_spot_y >= first_surface->h) hot_spot_y = first_surface->h - 1;
				}
				#endif

				cursor->m_hotX = hot_spot_x;
				cursor->m_hotY = hot_spot_y;
				// GeneralsX @bugfix felipebraz 28/04/2026 Use SDL native animated cursor to ensure frame playback.
				cursor->m_cursor = SDL_CreateAnimatedCursor(frame_infos, frame_index, hot_spot_x, hot_spot_y);
				if (!cursor->m_cursor)
				{
					DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: SDL_CreateAnimatedCursor failed [%s]", SDL_GetError()));
					#ifdef __linux__
					// GeneralsX @bugfix BenderAI 11/05/2026 Fallback to static cursor when ANI animation fails.
					if (first_surface)
					{
						cursor->m_cursor = SDL_CreateColorCursor(first_surface, hot_spot_x, hot_spot_y);
					}
					if (!cursor->m_cursor)
					{
						DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: SDL_CreateColorCursor fallback failed [%s]", SDL_GetError()));
						delete cursor;
						delete[] file_buffer;
						return NULL;
					}
					#else
					delete cursor;
					delete[] file_buffer;
					return NULL;
					#endif
				}
			}

			break;
		}
		else
		{
			DEBUG_LOG(("SDL3Mouse::loadCursorFromFile: Unhandled chunk"));
		}
		chunk = getNextChunk(chunk);
	}

#ifdef _DEBUG
	size_t loaded_frames = 0;
	for (int i = 0; i < MAX_2D_CURSOR_ANIM_FRAMES; i++)
	{
		if (cursor->m_frameSurfaces[i])
			loaded_frames++;
	}

	// DEBUG_ASSERTCRASH(loaded_frames == cursor->m_frameCount, ("Loaded frames do not match header"));
#endif

	delete[] file_buffer;
	return cursor;
}

/**
 * Initialize mouse subsystem
 */
void SDL3Mouse::init(void)
{
	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "INFO: SDL3Mouse::init()\n");

	// Call parent init (loads cursor info from INI, etc.)
	Mouse::init();

	// SDL3 always reports absolute window pixel coordinates (not deltas).
	// Without this flag, Mouse::processMouseEvent() uses MOUSE_MOVE_RELATIVE mode
	// and ADDS the incoming coordinate to the current tracked position, causing the
	// internal cursor to drift infinitely away from the visible cursor.
	// fighter19 / Win32Mouse both set this; we MUST too.
	// GeneralsX @bugfix felipebraz 21/02/2026 Fix mouse click never registering on UI
	m_inputMovesAbsolute = TRUE;

	// Show cursor by default
	SDL_ShowCursor();
	m_IsVisible = true;

	// Clear event buffer - Fighter19 pattern
	// GeneralsX @refactor felipebraz 16/02/2026
	memset(m_eventBuffer, 0, sizeof(m_eventBuffer));
	m_nextFreeIndex = 0;
	m_nextGetIndex = 0;

	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "INFO: SDL3Mouse::init() complete\n");
}

/**
 * Reset mouse to default state
 */
void SDL3Mouse::reset(void)
{
	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "DEBUG: SDL3Mouse::reset()\n");

	Mouse::reset();

	releaseCapture();
	SDL_ShowCursor();
	m_IsVisible = true;

	// Clear event buffer - Fighter19 pattern
	// GeneralsX @refactor felipebraz 16/02/2026
	memset(m_eventBuffer, 0, sizeof(m_eventBuffer));
	m_nextFreeIndex = 0;
	m_nextGetIndex = 0;
}

/**
 * Update mouse state (called per-frame)
 */
void SDL3Mouse::update(void)
{
	// Call parent update (processes events, updates m_currMouse)
	Mouse::update();
}

/**
 * Initialize cursor resources (load cursor images from ANI files)
 * GeneralsX @bugfix BenderAI 22/02/2026 Port fighter19 cursor loading
 */
void SDL3Mouse::initCursorResources(void)
{
	for (Int cursor=FIRST_CURSOR; cursor<NUM_MOUSE_CURSORS; cursor++)
	{
		for (Int direction=0; direction<m_cursorInfo[cursor].numDirections; direction++)
		{	if (!cursorResources[cursor][direction] && !m_cursorInfo[cursor].textureName.isEmpty())
			{	//this cursor has never been loaded before.
				char resourcePath[256];
				//Check if this is a directional cursor
				if (m_cursorInfo[cursor].numDirections > 1)
					sprintf(resourcePath,"Data/Cursors/%s%d.ani",m_cursorInfo[cursor].textureName.str(),direction);
				else
					sprintf(resourcePath,"Data/Cursors/%s.ani",m_cursorInfo[cursor].textureName.str());

				cursorResources[cursor][direction]=loadCursorFromFile(resourcePath);
				DEBUG_ASSERTCRASH(cursorResources[cursor][direction], ("MissingCursor %s\n",resourcePath));
			}
		}
	}
}

#if defined(__ANDROID__)
// The macOS-style arrow, defined with the touchpad's cursor drawing further down.
static bool gxIsPlainPointer(Int cursor);
static SDL_Cursor *gxMacArrowCursor();
#endif

/**
 * Set mouse cursor type
 * GeneralsX @bugfix BenderAI 22/02/2026 Implement cursor animation and selection
 */
void SDL3Mouse::setCursor(MouseCursor cursor)
{
	// extend
	Mouse::setCursor( cursor );

	if (m_LostFocus)  // GeneralsX @bugfix BenderAI 22/02/2026 Fix case: m_LostFocus not m_lostFocus
		return;	//stop messing with mouse cursor if we don't have focus.

	bool bUseDefaultCursor = false;
	if (cursor == NONE || !m_visible)
	{
		bUseDefaultCursor = true;
	}
#if defined(__ANDROID__)
	// GeneralsX @feature ZH Commander 11/10/2026 A real mouse's plain pointer is the macOS-style
	// arrow (gxMacArrowCursor), sized by the launcher's cursor size.
	else if (gxIsPlainPointer(cursor) && gxMacArrowCursor() != nullptr)
	{
		SDL_SetCursor(gxMacArrowCursor());
	}
#endif
	else
	{
		AnimatedCursor* currentCursor = cursorResources[cursor][m_directionFrame];
		if (currentCursor && currentCursor->m_cursor)
		{
			SDL_SetCursor(currentCursor->m_cursor);
		}
		else
		{
			bUseDefaultCursor = true;
		}
	}

	if (bUseDefaultCursor)
	{
		if (cursorResources[NORMAL][0] && cursorResources[NORMAL][0]->m_cursor)
		{
			SDL_SetCursor(cursorResources[NORMAL][0]->m_cursor);
		}
		else
		{
			// Fallback to SDL's default cursor in case of failure
			// This is to avoid crashing in case of case sensitivity issues
			SDL_SetCursor(SDL_GetDefaultCursor());
		}
	}

	// save current cursor
	m_currentCursor = cursor;
}

/**
 * Set cursor visibility
 * GeneralsX @bugfix BenderAI 07/03/2026 - On SDL3, the cursor IS the SDL system cursor
 * (no W3D rendering). We must always tell the parent that cursor is visible, otherwise
 * setCursor() checks !m_visible and falls back to NORMAL cursor for all types (attack,
 * move, etc. all show the same default cursor).
 */
void SDL3Mouse::setVisibility(Bool visible)
{
	// Always tell parent cursor is visible so setCursor() selects the correct cursor type.
	// On Windows, setVisibility(FALSE) hides OS cursor so W3D draws its own. On SDL3
	// there is no W3D cursor rendering, so m_visible must stay TRUE.
	Mouse::setVisibility(TRUE);
	m_IsVisible = visible;

	// Always keep SDL cursor visible since it's the only cursor renderer.
	SDL_ShowCursor();
}

/**
 * Handle window losing focus
 */
void SDL3Mouse::loseFocus()
{
	m_LostFocus = true;           // GeneralsX @bugfix felipebraz 18/02/2026 Set focus flag
	releaseCapture();
}

/**
 * Handle window regaining focus
 */
void SDL3Mouse::regainFocus()
{
	m_LostFocus = false;          // GeneralsX @bugfix felipebraz 18/02/2026 Clear focus flag
	// Capture may be re-enabled by game logic
}

#if (defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE) || defined(__ANDROID__)

// GeneralsX @bugfix Android port 06/09/2026 See the declaration comment: this
// updates the position preview drawing reads, and nothing else. No message is
// appended to the stream here, so no translator sees an event and nothing about
// hover, scrolling, selection or clicking changes.
void SDL3Mouse::setTouchCursorPos(Int x, Int y)
{
	m_currMouse.pos.x = x;
	m_currMouse.pos.y = y;
}

/**
 * GeneralsX @feature Android port 01/08/2026 See the declaration comment in
 * SDL3Mouse.h: touch input on this platform drives the message stream
 * directly from SDL3GameEngine.cpp, so this object never receives real
 * events (nothing calls addSDLEvent() on it here) and its inherited
 * per-frame MSG_RAW_MOUSE_POSITION heartbeat would only inject a second,
 * stale position source. No-op.
 */
void SDL3Mouse::createStreamMessages()
{
	// GeneralsX @feature Android port 05/10/2026 ...unless the player chose a pointer in the
	// launcher (GXMouseMode.h, issue #39). Then the events in this object's buffer -- a real
	// mouse's, and the touchpad's -- become the same raw mouse messages they do on a PC, and the
	// touch layer stays out of the way (SDL3GameEngine.cpp sends fingers to the touchpad instead).
	// GeneralsX @feature ZH Commander 11/10/2026 ...and whenever a real mouse was used last
	// (GXPointerActive), with no switch: a mouse plays exactly as on a PC.
	if (GXPointerActive()) {
		Mouse::createStreamMessages();
	}
}

// GeneralsX @feature Android port 05/10/2026 One cursor frame as an image the display can draw:
// the ICO frame SDL_image decoded, copied into a texture the size rounded up to a power of two.
static Image *gxCursorFrameImage(SDL_Surface *frame, TextureClass **outTexture)
{
	SDL_Surface *argb = SDL_ConvertSurface(frame, SDL_PIXELFORMAT_ARGB8888);
	if (argb == nullptr) {
		return nullptr;
	}
	int texW = 1, texH = 1;
	while (texW < argb->w) texW <<= 1;
	while (texH < argb->h) texH <<= 1;

	TextureClass *texture = MSGNEW("TextureClass") TextureClass(texW, texH, WW3D_FORMAT_A8R8G8B8, MIP_LEVELS_1);
	SurfaceClass *surface = texture ? texture->Get_Surface_Level() : nullptr;
	int pitch = 0;
	UnsignedByte *bits = (surface && surface->Get_Bytes_Per_Pixel() == 4) ? (UnsignedByte *)surface->Lock(&pitch) : nullptr;
	if (bits == nullptr) {
		REF_PTR_RELEASE(surface);
		REF_PTR_RELEASE(texture);
		SDL_DestroySurface(argb);
		return nullptr;
	}
	for (int y = 0; y < texH; ++y) {
		UnsignedInt *row = (UnsignedInt *)(bits + y * pitch);
		const UnsignedInt *src = (y < argb->h) ? (const UnsignedInt *)((const UnsignedByte *)argb->pixels + y * argb->pitch) : nullptr;
		for (int x = 0; x < texW; ++x) {
			row[x] = (src != nullptr && x < argb->w) ? src[x] : 0;
		}
	}
	surface->Unlock();
	REF_PTR_RELEASE(surface);

	Image *image = newInstance(Image);
	image->setName("GXTouchpadCursor");
	image->setStatus(IMAGE_STATUS_RAW_TEXTURE);
	image->setRawTextureData(texture);
	Region2D uv;
	uv.lo.x = 0.0f;
	uv.lo.y = 0.0f;
	uv.hi.x = (Real)argb->w / texW;
	uv.hi.y = (Real)argb->h / texH;
	image->setUV(&uv);
	image->setTextureWidth(texW);
	image->setTextureHeight(texH);
	ICoord2D size;
	size.x = argb->w;
	size.y = argb->h;
	image->setImageSize(&size);
	SDL_DestroySurface(argb);
	*outTexture = texture;
	return image;
}

// GeneralsX @feature ZH Commander 11/10/2026 The plain pointer as a macOS-style arrow (owner's
// request): black, a white outline and a soft shadow, drawn for the normal and arrow cursors only.
// The game's own cursors still show what a click will do (attack, move, sell, ...). Rendered from
// its outline at any height, so it stays sharp both as the touchpad's drawn cursor and as the
// system pointer a real mouse moves. The hot spot is the tip, at (margin, margin).
static int gxMacArrowMargin(int height)
{
	return height / 8 + 2;
}

static SDL_Surface *gxMacArrowSurface(int height)
{
	// The arrow's outline in units of its height, the tip at the origin.
	static const float shape[][2] = {
		{ 0.000f, 0.000f }, { 0.000f, 0.860f }, { 0.195f, 0.680f }, { 0.335f, 1.000f },
		{ 0.455f, 0.950f }, { 0.330f, 0.655f }, { 0.585f, 0.655f },
	};
	const int count = (int)(sizeof(shape) / sizeof(shape[0]));
	const float h = (float)height;
	const float border = h * 0.065f;
	const float shadowX = h * 0.03f, shadowY = h * 0.05f;
	const int margin = gxMacArrowMargin(height);
	const int w = (int)(0.6f * h) + margin * 2;
	const int hh = height + margin * 2;

	SDL_Surface *surface = SDL_CreateSurface(w, hh, SDL_PIXELFORMAT_ARGB8888);
	if (surface == nullptr) {
		return nullptr;
	}

	// Inside the arrow, and how far from its outline, for a point in arrow pixels.
	auto sample = [&](float px, float py, float &edge) -> bool {
		bool inside = false;
		edge = 1e9f;
		for (int i = 0, j = count - 1; i < count; j = i++) {
			const float ax = shape[j][0] * h, ay = shape[j][1] * h;
			const float bx = shape[i][0] * h, by = shape[i][1] * h;
			if ((by > py) != (ay > py) && px < (ax - bx) * (py - by) / (ay - by) + bx) {
				inside = !inside;
			}
			const float ex = ax - bx, ey = ay - by;
			const float len2 = ex * ex + ey * ey;
			float t = len2 > 0.0f ? ((px - bx) * ex + (py - by) * ey) / len2 : 0.0f;
			t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
			const float dx = px - (bx + t * ex), dy = py - (by + t * ey);
			const float d = sqrtf(dx * dx + dy * dy);
			if (d < edge) {
				edge = d;
			}
		}
		return inside;
	};

	const int SS = 4;  // 4x4 samples a pixel
	for (int y = 0; y < hh; ++y) {
		Uint32 *row = (Uint32 *)((Uint8 *)surface->pixels + y * surface->pitch);
		for (int x = 0; x < w; ++x) {
			float black = 0.0f, white = 0.0f, shadow = 0.0f;
			for (int sy = 0; sy < SS; ++sy) {
				for (int sx = 0; sx < SS; ++sx) {
					const float px = (float)(x - margin) + (sx + 0.5f) / SS;
					const float py = (float)(y - margin) + (sy + 0.5f) / SS;
					float edge;
					const bool inside = sample(px, py, edge);
					if (inside && edge > border) {
						black += 1.0f;
					} else if (inside || edge <= border) {
						white += 1.0f;
					} else {
						float shadowEdge;
						if (sample(px - shadowX, py - shadowY, shadowEdge) || shadowEdge <= border * 2.0f) {
							shadow += 1.0f;
						}
					}
				}
			}
			const float n = (float)(SS * SS);
			const float arrowA = (black + white) / n;
			const float shadowA = shadow / n * 0.35f;
			const float a = arrowA + shadowA * (1.0f - arrowA);
			const float grey = (a > 0.0f) ? (white / n) / a : 0.0f;
			const Uint32 A = (Uint32)(a * 255.0f + 0.5f);
			const Uint32 G = (Uint32)(grey * 255.0f + 0.5f);
			row[x] = (A << 24) | (G << 16) | (G << 8) | G;
		}
	}
	return surface;
}

static bool gxIsPlainPointer(Int cursor)
{
	return cursor == Mouse::NORMAL || cursor == Mouse::ARROW;
}

// The system pointer for a real mouse, at the cursor size the launcher chose. Remade when the size
// changes.
static SDL_Cursor *gxMacArrowCursor()
{
	static SDL_Cursor *cursor = nullptr;
	static int madeHeight = 0;
	const float screenH = TheDisplay != nullptr ? (float)TheDisplay->getHeight() : 1080.0f;
	const int height = (int)(screenH * 0.026f * (float)GXMouseCursorPercent() / 100.0f + 0.5f);
	if (cursor != nullptr && madeHeight == height) {
		return cursor;
	}
	SDL_Surface *surface = gxMacArrowSurface(height);
	if (surface == nullptr) {
		return cursor;
	}
	SDL_Cursor *made = SDL_CreateColorCursor(surface, gxMacArrowMargin(height), gxMacArrowMargin(height));
	SDL_DestroySurface(surface);
	if (made != nullptr) {
		if (cursor != nullptr) {
			SDL_DestroyCursor(cursor);
		}
		cursor = made;
		madeHeight = height;
	}
	return cursor;
}

/**
 * GeneralsX @feature Android port 05/10/2026 Draw the touchpad pointer (issue #39).
 *
 * Android draws a pointer only for a real mouse. A finger moving the cursor is no mouse to it, so
 * while the last pointer input came from the touchpad the game draws the cursor itself, after the
 * interface, with the cursor the game chose (attack, move, sell, ...) and its own hot spot. The
 * cursor files are 32 pixels; scaled up with the screen's height so it stays visible on a phone.
 */
void SDL3Mouse::draw()
{
	Mouse::draw();
	if (!GXMouseModeEnabled() || !m_touchpadPointer || TheDisplay == nullptr) {
		return;
	}
	if (m_currentCursor == NONE) {
		return;	// the game hides the pointer (movies, cinematics)
	}

	// GeneralsX @bugfix Android port 05/10/2026 The scroll cursor points where the view moves
	// (owner: "the arrows always point right"). Nothing set the direction on this platform; this
	// is W3DMouse::setCursorDirection's rule: frame 0 points right, the rest go clockwise.
	if (m_currentCursor >= FIRST_CURSOR && m_currentCursor < NUM_MOUSE_CURSORS) {
		const Int numDirections = m_cursorInfo[m_currentCursor].numDirections;
		m_directionFrame = 0;
		if (numDirections > 1 && TheInGameUI != nullptr && TheInGameUI->isScrolling()) {
			Coord2D offset = TheInGameUI->getScrollAmount();
			if (offset.x != 0.0f || offset.y != 0.0f) {
				Real theta = atan2f(offset.y, offset.x);
				theta = fmodf(theta + PI * 2.0f, PI * 2.0f);
				m_directionFrame = (Int)(theta / (2.0f * PI / (Real)numDirections) + 0.5f);
				if (m_directionFrame >= numDirections || m_directionFrame >= MAX_2D_CURSOR_DIRECTIONS) {
					m_directionFrame = 0;
				}
			}
		}
	}

	AnimatedCursor *cursor = nullptr;
	if (m_currentCursor >= FIRST_CURSOR && m_currentCursor < NUM_MOUSE_CURSORS &&
	    m_directionFrame >= 0 && m_directionFrame < MAX_2D_CURSOR_DIRECTIONS) {
		cursor = cursorResources[m_currentCursor][m_directionFrame];
	}
	if (cursor == nullptr || cursor->m_frameCount <= 0) {
		cursor = cursorResources[NORMAL][0];
	}

	// GeneralsX @tweak Android port 05/10/2026 Sized like the interface: the cursor art is made for
	// 800x600, so scale with the screen against that, and by the launcher's interface size
	// (GX_UI_SCALE, percent) -- it stayed small when the interface grew (owner's report).
	// GeneralsX @tweak Android port 05/10/2026 A fifth smaller at 100% (owner: "a little smaller"),
	// and the launcher's cursor-size slider on top (GXMouseCursorPercent, 50-200%).
	Real scale = (Real)TheDisplay->getHeight() / 600.0f * 0.8f;
	const char *uiScale = getenv("GX_UI_SCALE");
	if (uiScale != nullptr && atoi(uiScale) > 0) {
		scale *= (Real)atoi(uiScale) / 100.0f;
	}
	scale *= (Real)GXMouseCursorPercent() / 100.0f;
	if (scale < 0.5f) scale = 0.5f;
	if (scale > 6.0f) scale = 6.0f;
	const Int x = m_currMouse.pos.x;
	const Int y = m_currMouse.pos.y;

	// GeneralsX @feature ZH Commander 11/10/2026 The plain pointer is the macOS-style arrow, drawn
	// from one 64-pixel render, at the same height as a real mouse's (gxMacArrowCursor).
	if (gxIsPlainPointer(m_currentCursor)) {
		static Image *macImage = nullptr;
		static TextureClass *macTexture = nullptr;
		const int RENDER_HEIGHT = 64;
		if (macImage == nullptr) {
			SDL_Surface *surface = gxMacArrowSurface(RENDER_HEIGHT);
			if (surface != nullptr) {
				macImage = gxCursorFrameImage(surface, &macTexture);
				SDL_DestroySurface(surface);
			}
		}
		if (macImage != nullptr) {
			const Real target = (Real)TheDisplay->getHeight() * 0.026f * (Real)GXMouseCursorPercent() / 100.0f;
			const Real s = target / (Real)RENDER_HEIGHT;
			const Int margin = (Int)(gxMacArrowMargin(RENDER_HEIGHT) * s);
			const Int w = (Int)(macImage->getImageWidth() * s);
			const Int h = (Int)(macImage->getImageHeight() * s);
			TheDisplay->drawImage(macImage, x - margin, y - margin, x - margin + w, y - margin + h);
			return;
		}
	}

	if (cursor != nullptr && cursor->m_frameCount > 0) {
		const Uint64 rate = cursor->m_frameRate > 0 ? (Uint64)cursor->m_frameRate : 4;
		int frame = (int)(((SDL_GetTicks() * 60 / 1000) / rate) % (Uint64)cursor->m_frameCount);
		if (frame < 0 || frame >= MAX_2D_CURSOR_ANIM_FRAMES || cursor->m_frameSurfaces[frame] == nullptr) {
			frame = 0;
		}
		if (cursor->m_gxImages[frame] == nullptr && cursor->m_frameSurfaces[frame] != nullptr) {
			cursor->m_gxImages[frame] = gxCursorFrameImage(cursor->m_frameSurfaces[frame], &cursor->m_gxTextures[frame]);
		}
		const Image *image = cursor->m_gxImages[frame];
		if (image != nullptr) {
			const Int w = (Int)(image->getImageWidth() * scale);
			const Int h = (Int)(image->getImageHeight() * scale);
			const Int left = x - (Int)(cursor->m_hotX * scale);
			const Int top = y - (Int)(cursor->m_hotY * scale);
			TheDisplay->drawImage(image, left, top, left + w, top + h);
			return;
		}
	}

	// No cursor art: a plain cross, so the pointer is never invisible.
	const Int r = (Int)(10 * scale);
	TheDisplay->drawLine(x - r, y, x + r, y, 2.0f * scale, 0xFFFFFFFF);
	TheDisplay->drawLine(x, y - r, x, y + r, 2.0f * scale, 0xFFFFFFFF);
}
#endif

/**
 * Capture mouse (confine to window)
 */
void SDL3Mouse::capture(void)
{
	if (!m_Window || m_IsCaptured) {
		return;
	}

	// SDL3: Capture mouse to window
	SDL_CaptureMouse(true);

	// SDL3: Grab mouse (confine to window)
	SDL_SetWindowMouseGrab(m_Window, true);

	m_IsCaptured = true;

	// @fix Notify base class so isCursorCaptured() returns true.
	// This is required for edge scrolling (canScrollAtScreenEdge checks isCursorCaptured).
	onCursorCaptured(true);
}

/**
 * Release mouse capture
 */
void SDL3Mouse::releaseCapture(void)
{
	if (!m_IsCaptured) {
		return;
	}

	SDL_CaptureMouse(false);
	if (m_Window) {
		SDL_SetWindowMouseGrab(m_Window, false);
	}

	m_IsCaptured = false;

	// @fix Notify base class so isCursorCaptured() returns false.
	onCursorCaptured(false);
}

/**
 * Get next mouse event from buffer
 * Called by Mouse::update() in parent class
 * Fighter19 pattern: check for SDL_EVENT_FIRST sentinel (value 0 = empty)
 *
 * @param result Output MouseIO structure
 * @param flush If true, flush all events
 * @return MOUSE_OK if event retrieved, MOUSE_NONE if buffer empty
 */
UnsignedByte SDL3Mouse::getMouseEvent(MouseIO *result, Bool flush)
{
	// Check if buffer is empty: if event type is SDL_EVENT_FIRST (0), slot is empty
	// GeneralsX @refactor felipebraz 16/02/2026 Fighter19 pattern: raw SDL_Event with sentinel
	if (m_eventBuffer[m_nextGetIndex].type == SDL_EVENT_FIRST) {
		return MOUSE_NONE;
	}

	// Translate SDL_Event to MouseIO
	// GeneralsX @refactor felipebraz 16/02/2026 Use unified translateEvent()
	translateEvent(m_nextGetIndex, result);

	// Mark this slot as empty (sentinel)
	m_eventBuffer[m_nextGetIndex].type = SDL_EVENT_FIRST;

	// Advance read position (circular buffer)
	m_nextGetIndex = (m_nextGetIndex + 1) % MAX_SDL3_MOUSE_EVENTS;

	return MOUSE_OK;
}

/**
 * Add SDL3 mouse motion event to buffer (LEGACY - being phased out)
 * Refactored to use unified addSDLEvent() internally
 * GeneralsX @refactor felipebraz 16/02/2026 Transitioning to Fighter19 model
 */
void SDL3Mouse::addSDL3MouseMotionEvent(const SDL_MouseMotionEvent& event)
{
	// Wrap in SDL_Event and call unified handler
	SDL_Event sdlEvent;
	memset(&sdlEvent, 0, sizeof(sdlEvent));
	sdlEvent.type = SDL_EVENT_MOUSE_MOTION;
	sdlEvent.motion = event;
	addSDLEvent(&sdlEvent);
}

/**
 * Add SDL3 mouse button event to buffer (LEGACY - being phased out)
 * Refactored to use unified addSDLEvent() internally
 * GeneralsX @refactor felipebraz 16/02/2026 Transitioning to Fighter19 model
 */
void SDL3Mouse::addSDL3MouseButtonEvent(const SDL_MouseButtonEvent& event)
{
	// Wrap in SDL_Event and call unified handler
	SDL_Event sdlEvent;
	memset(&sdlEvent, 0, sizeof(sdlEvent));
	sdlEvent.type = event.down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP;
	sdlEvent.button = event;
	addSDLEvent(&sdlEvent);
}

/**
 * Add SDL3 mouse wheel event to buffer (LEGACY - being phased out)
 * Refactored to use unified addSDLEvent() internally
 * GeneralsX @refactor felipebraz 16/02/2026 Transitioning to Fighter19 model
 */
void SDL3Mouse::addSDL3MouseWheelEvent(const SDL_MouseWheelEvent& event)
{
	// Wrap in SDL_Event and call unified handler
	SDL_Event sdlEvent;
	memset(&sdlEvent, 0, sizeof(sdlEvent));
	sdlEvent.type = SDL_EVENT_MOUSE_WHEEL;
	sdlEvent.wheel = event;
	addSDLEvent(&sdlEvent);
}

/**
 * Translate SDL3 motion event to MouseIO
 */
void SDL3Mouse::translateMotionEvent(const SDL_MouseMotionEvent& event, MouseIO *result)
{
	result->pos.x = (Int)event.x;
	result->pos.y = (Int)event.y;
	result->deltaPos.x = (Int)event.xrel;
	result->deltaPos.y = (Int)event.yrel;
	// GeneralsX @bugfix felipebraz 18/02/2026 Normalize timestamp to milliseconds (SDL3 uses nanoseconds)
	result->time = (Uint32)(event.timestamp / 1000000);

	// No button state change on motion
	result->leftState = MBS_None;
	result->rightState = MBS_None;
	result->middleState = MBS_None;
	result->wheelPos = 0;
}

/**
 * Translate SDL3 button event to MouseIO
 */
void SDL3Mouse::translateButtonEvent(const SDL_MouseButtonEvent& event, MouseIO *result)
{
	result->pos.x = (Int)event.x;
	result->pos.y = (Int)event.y;
	result->deltaPos.x = 0;
	result->deltaPos.y = 0;
	// GeneralsX @bugfix felipebraz 18/02/2026 Normalize timestamp to milliseconds (SDL3 uses nanoseconds)
	result->time = (Uint32)(event.timestamp / 1000000);
	result->wheelPos = 0;

	// Initialize all button states to None
	result->leftState = MBS_None;
	result->rightState = MBS_None;
	result->middleState = MBS_None;

	// GeneralsX @bugfix felipebraz 18/02/2026 Initialize frame tracking for replay determinism
	result->leftFrame = 0;
	result->rightFrame = 0;
	result->middleFrame = 0;

	MouseButtonState state = event.down ? MBS_Down : MBS_Up;

	// GeneralsX @bugfix BenderAI 17/02/2026 Debug mouse button events
	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "[MOUSE] Button event: button=%d state=%s pos=(%d,%d)\n",
	//	event.button, event.down ? "DOWN" : "UP", (Int)event.x, (Int)event.y);

	// Get current frame for replay determinism
	// GeneralsX @bugfix felipebraz 18/02/2026 Use game frame instead of timestamp
	UnsignedInt currentFrame = (TheGameLogic) ? TheGameLogic->getFrame() : 1;

	// Map SDL3 button to MouseIO button
	switch (event.button) {
		case SDL_BUTTON_LEFT:
			// GeneralsX @bugfix BenderAI 07/03/2026 Only detect double-click on DOWN events
			// (matching right/middle button logic). Without this check, rapid clicks cause
			// UP events with clicks>=2 to become MBS_DoubleClick, which GadgetPushButton
			// doesn't handle, leaving buttons stuck in WIN_STATE_SELECTED (white overlay).
			if (event.down && event.clicks >= 2) {
				result->leftState = MBS_DoubleClick;
			} else {
				result->leftState = state;
			}
			result->leftFrame = currentFrame;  // GeneralsX @bugfix felipebraz 18/02/2026 Track frame for replay
			break;

		case SDL_BUTTON_RIGHT:
			// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
			// fprintf(stderr, "[MOUSE] Right button: %s\n", event.down ? "DOWN" : "UP");
			// GeneralsX @bugfix felipebraz 18/02/2026 Use native SDL3 clicks for right button too
			if (event.down && event.clicks >= 2) {
				result->rightState = MBS_DoubleClick;
				// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
				// fprintf(stderr, "[MOUSE] Right double-click detected (clicks=%d)\n", event.clicks);
			} else {
				result->rightState = state;
			}
			result->rightFrame = currentFrame;  // GeneralsX @bugfix felipebraz 18/02/2026 Track frame for replay
			break;

		case SDL_BUTTON_MIDDLE:
			// GeneralsX @bugfix felipebraz 18/02/2026 Support double-click for middle button
			if (event.down && event.clicks >= 2) {
				result->middleState = MBS_DoubleClick;
			} else {
				result->middleState = state;
			}
			result->middleFrame = currentFrame;  // GeneralsX @bugfix felipebraz 18/02/2026 Track frame for replay
			break;
	}
}

/**
 * Translate SDL3 wheel event to MouseIO
 */
void SDL3Mouse::translateWheelEvent(const SDL_MouseWheelEvent& event, MouseIO *result)
{
	// SDL3 mouse position not provided in wheel event, get current position
	float mouseX, mouseY;
	SDL_GetMouseState(&mouseX, &mouseY);

	result->pos.x = (Int)mouseX;
	result->pos.y = (Int)mouseY;
	result->deltaPos.x = 0;
	result->deltaPos.y = 0;
	// GeneralsX @bugfix felipebraz 18/02/2026 Normalize timestamp to milliseconds (SDL3 uses nanoseconds)
	result->time = (Uint32)(event.timestamp / 1000000);

	// SDL3 wheel: positive = up/away, negative = down/toward user
	// Multiply by MOUSE_WHEEL_DELTA (120) to match Windows behavior
	result->wheelPos = (Int)(event.y * MOUSE_WHEEL_DELTA);

	result->leftState = MBS_None;
	result->rightState = MBS_None;
	result->middleState = MBS_None;
}

/**
 * Scale raw SDL3 window pixel coordinates to game internal resolution.
 * This is CRITICAL for correct hit-testing: the game's UI layout is based on
 * its internal resolution (e.g. 800x600), but SDL reports pixel coordinates
 * relative to the actual window size (e.g. 1920x1080). Without scaling, clicks
 * land at the wrong position and the game ignores them.
 *
 * Port of fighter19's scaleMouseCoordinates() - direct adaptation.
 *
 * GeneralsX @bugfix felipebraz 20/02/2026 Fix mouse click coordinates not matching UI layout
 */
void SDL3Mouse::scaleMouseCoordinates(int rawX, int rawY, Uint32 windowID, int& scaledX, int& scaledY)
{
	SDL_Window* window = SDL_GetWindowFromID(windowID);
	if (!window || !TheDisplay) {
		scaledX = rawX;
		scaledY = rawY;
		return;
	}

	int windowWidth = 0, windowHeight = 0;
	SDL_GetWindowSize(window, &windowWidth, &windowHeight);

	if (windowWidth <= 0 || windowHeight <= 0) {
		scaledX = rawX;
		scaledY = rawY;
		return;
	}

	int internalWidth  = TheDisplay->getWidth();
	int internalHeight = TheDisplay->getHeight();

	int pbX, pbY, pbW, pbH;
	if (TheDisplay->getViewportRect(pbX, pbY, pbW, pbH)) {
		int clampedX = rawX - pbX;
		if (clampedX < 0) clampedX = 0;
		if (clampedX > pbW) clampedX = pbW;
		int clampedY = rawY - pbY;
		if (clampedY < 0) clampedY = 0;
		if (clampedY > pbH) clampedY = pbH;
		scaledX = static_cast<int>(clampedX * static_cast<float>(internalWidth) / static_cast<float>(pbW));
		scaledY = static_cast<int>(clampedY * static_cast<float>(internalHeight) / static_cast<float>(pbH));
		return;
	}

	float factorX = static_cast<float>(internalWidth)  / static_cast<float>(windowWidth);
	float factorY = static_cast<float>(internalHeight) / static_cast<float>(windowHeight);

	scaledX = static_cast<int>(rawX * factorX);
	scaledY = static_cast<int>(rawY * factorY);
}

/**
 * Add raw SDL_Event to mouse event buffer
 * Fighter19 pattern: unified method that accepts any SDL_Event
 * Called by SDL3GameEngine::serviceWindowsOS() directly from event loop
 *
 * @param event Raw SDL_Event from SDL_PollEvent()
 *
 * GeneralsX @refactor felipebraz 16/02/2026 Fighter19 event model
 */
void SDL3Mouse::addSDLEvent(SDL_Event *event)
{
	if (!event) {
		return;
	}

	// Filter only mouse-related events
	if (event->type != SDL_EVENT_MOUSE_MOTION &&
	    event->type != SDL_EVENT_MOUSE_BUTTON_DOWN &&
	    event->type != SDL_EVENT_MOUSE_BUTTON_UP &&
	    event->type != SDL_EVENT_MOUSE_WHEEL) {
		return;  // Not a mouse event, ignore
	}

#if (defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE) || defined(__ANDROID__)
	// GeneralsX @feature Android port 05/10/2026 Whose pointer it is now: the touchpad's (drawn by
	// the game) or a real mouse's (drawn by Android).
	if (event->type == SDL_EVENT_MOUSE_MOTION) {
		m_touchpadPointer = (event->motion.which == TOUCHPAD_MOUSE_ID);
	} else if (event->type == SDL_EVENT_MOUSE_BUTTON_DOWN || event->type == SDL_EVENT_MOUSE_BUTTON_UP) {
		m_touchpadPointer = (event->button.which == TOUCHPAD_MOUSE_ID);
	}
#endif

	// Check if buffer is full
	UnsignedInt nextFreeIndex = (m_nextFreeIndex + 1) % MAX_SDL3_MOUSE_EVENTS;
	if (nextFreeIndex == m_nextGetIndex) {
		// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
		// fprintf(stderr, "WARNING: SDL3Mouse::addSDLEvent() buffer full (dropped event)\n");
		return;
	}

	// Copy entire event to buffer
	m_eventBuffer[m_nextFreeIndex] = *event;

	// GeneralsX @bugfix BenderAI 18/02/2026 Temporarily disable debug logging (Phase 1.8)
	// fprintf(stderr, "DEBUG: SDL3Mouse::addSDLEvent() type=%u index=%u\n", event->type, m_nextFreeIndex);

	// Advance write position (circular buffer)
	m_nextFreeIndex = nextFreeIndex;
}

/**
 * Translate SDL_Event at given buffer index to MouseIO
 * Unified translation method that handles all mouse event types
 *
 * @param eventIndex Index in m_eventBuffer to translate
 * @param result Output MouseIO structure
 *
 * GeneralsX @refactor felipebraz 16/02/2026 Unified translation
 */
void SDL3Mouse::translateEvent(UnsignedInt eventIndex, MouseIO *result)
{
	if (eventIndex >= MAX_SDL3_MOUSE_EVENTS || !result) {
		return;
	}

	const SDL_Event& event = m_eventBuffer[eventIndex];

	// Raw window-pixel coordinates and window ID, extracted per event type
	int rawX = 0, rawY = 0;
	Uint32 windowID = 0;

	// Switch on event type and delegate to appropriate translation method
	switch (event.type) {
		case SDL_EVENT_MOUSE_MOTION:
			translateMotionEvent(event.motion, result);
			rawX     = (int)event.motion.x;
			rawY     = (int)event.motion.y;
			windowID = event.motion.windowID;
			break;
		case SDL_EVENT_MOUSE_BUTTON_DOWN:
		case SDL_EVENT_MOUSE_BUTTON_UP:
			translateButtonEvent(event.button, result);
			rawX     = (int)event.button.x;
			rawY     = (int)event.button.y;
			windowID = event.button.windowID;
			break;
		case SDL_EVENT_MOUSE_WHEEL:
			translateWheelEvent(event.wheel, result);
			rawX     = (int)event.wheel.mouse_x;
			rawY     = (int)event.wheel.mouse_y;
			windowID = event.wheel.windowID;
			break;
		default:
			// Should not happen (sentinel value)
			memset(result, 0, sizeof(*result));
			return;
	}

	// Scale from SDL window-pixel space to game internal resolution.
	// GeneralsX @bugfix felipebraz 20/02/2026 Without this, UI hit-testing fails because
	// the game checks clicks against its internal resolution (e.g. 800x600) but SDL
	// reports coordinates in actual window pixels (e.g. 1920x1080).
	int scaledX = 0, scaledY = 0;
	scaleMouseCoordinates(rawX, rawY, windowID, scaledX, scaledY);
	result->pos.x = scaledX;
	result->pos.y = scaledY;
}

#endif // !_WIN32
