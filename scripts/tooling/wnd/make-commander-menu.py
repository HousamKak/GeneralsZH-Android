#!/usr/bin/env python3
"""Writes android/app/src/main/assets/gamedata/Window/Menus/CommanderMenu.wnd.

The ZH Commander settings screen of the game's Options menu (CommanderMenu.cpp). Generated
rather than hand-written so every widget carries the same draw data as the retail Options menu
it sits next to: the dimmed screen, the dark panel with the blue border, the "Title",
"LabelRegular" and "MainButton" header templates, and the Buttons-Left/Middle/Right art. Text is
set at runtime by CommanderMenu.cpp, so no TEXT lines.

Usage: python3 scripts/tooling/wnd/make-commander-menu.py
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "gamedata", "Window", "Menus", "CommanderMenu.wnd")
PREFIX = "CommanderMenu.wnd:"

CLEAR = "IMAGE: NoImage, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0"


def draw_data(first, rest=None, count=9):
    rest = rest or []
    entries = [first] + rest
    entries += [CLEAR] * (count - len(entries))
    return entries


def block(key, entries, indent):
    pad = " " * (indent + len(key) + 3)
    lines = [f"{' ' * indent}{key} = {entries[0]},"]
    for i, e in enumerate(entries[1:], 1):
        lines.append(f"{pad}{e}{';' if i == len(entries) - 1 else ','}")
    return lines


def window(kind, name, rect, status, style, font, template, text_colors,
           enabled, disabled, hilite, indent, system="[None]", input_cb="[None]", extra=None):
    x1, y1, x2, y2 = rect
    i = " " * indent
    lines = [
        f"{i}WINDOWTYPE = {kind};",
        f"{i}SCREENRECT = UPPERLEFT: {x1} {y1},",
        f"{i}             BOTTOMRIGHT: {x2} {y2},",
        f"{i}             CREATIONRESOLUTION: 800 600;",
        f"{i}NAME = \"{PREFIX}{name}\";",
        f"{i}STATUS = {status};",
        f"{i}STYLE = {style};",
        f"{i}SYSTEMCALLBACK = \"{system}\";",
        f"{i}INPUTCALLBACK = \"{input_cb}\";",
        f"{i}TOOLTIPCALLBACK = \"[None]\";",
        f"{i}DRAWCALLBACK = \"[None]\";",
        f"{i}FONT = NAME: \"{font[0]}\", SIZE: {font[1]}, BOLD: {font[2]};",
        f"{i}HEADERTEMPLATE = \"{template}\";",
        f"{i}TOOLTIPDELAY = -1;",
        f"{i}TEXTCOLOR = ENABLED:  {text_colors[0]},",
        f"{i}            DISABLED: {text_colors[1]},",
        f"{i}            HILITE:   {text_colors[2]};",
    ]
    lines += block("ENABLEDDRAWDATA", enabled, indent)
    lines += block("DISABLEDDRAWDATA", disabled, indent)
    lines += block("HILITEDRAWDATA", hilite, indent)
    if extra:
        lines += [f"{i}{e}" for e in extra]
    return lines


def label(name, rect, size=12, template="LabelRegular", font="Arial", centered=False):
    colors = ("254 254 254 255, ENABLEDBORDER:  0 0 0 255",
              "192 192 192 255, DISABLEDBORDER: 64 64 64 255",
              "254 254 254 255, HILITEBORDER:   0 0 0 255")
    style = "STATICTEXT+MOUSETRACK"
    return window("STATICTEXT", name, rect, "ENABLED", style, (font, size, 0), template, colors,
                  draw_data("IMAGE: NoImage, COLOR: 2 2 2 0, BORDERCOLOR: 0 0 0 0"),
                  draw_data("IMAGE: NoImage, COLOR: 64 64 64 0, BORDERCOLOR: 192 192 192 0"),
                  draw_data("IMAGE: NoImage, COLOR: 0 128 0 0, BORDERCOLOR: 128 255 128 0"),
                  8, extra=[f"STATICTEXTDATA = CENTERED: {1 if centered else 0};"])


def button(name, rect, size=15):
    colors = ("255 255 255 255, ENABLEDBORDER:  0 0 0 255",
              "62 64 92 255, DISABLEDBORDER: 31 32 47 255",
              "186 255 12 255, HILITEBORDER:   0 2 0 255")
    enabled = [
        "IMAGE: Buttons-Left, COLOR: 255 0 0 255, BORDERCOLOR: 255 128 128 255",
        "IMAGE: NoImage, COLOR: 47 55 168 255, BORDERCOLOR: 254 254 254 255",
        CLEAR, CLEAR, CLEAR,
        "IMAGE: Buttons-Middle, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        "IMAGE: Buttons-Right, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        CLEAR, CLEAR,
    ]
    disabled = [
        "IMAGE: Buttons-Disabled-Left, COLOR: 128 128 128 255, BORDERCOLOR: 192 192 192 255",
        "IMAGE: NoImage, COLOR: 192 192 192 255, BORDERCOLOR: 128 128 128 255",
        CLEAR, CLEAR, CLEAR,
        "IMAGE: Buttons-Disabled-Middle, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        "IMAGE: Buttons-Disabled-Right, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        CLEAR, CLEAR,
    ]
    hilite = [
        "IMAGE: Buttons-HiLite-Left, COLOR: 209 253 4 255, BORDERCOLOR: 59 60 52 255",
        "IMAGE: Buttons-Pushed-Left, COLOR: 47 55 168 255, BORDERCOLOR: 254 254 254 255",
        CLEAR,
        "IMAGE: Buttons-Pushed-Middle, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        "IMAGE: Buttons-Pushed-Right, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        "IMAGE: Buttons-HiLite-Middle, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        "IMAGE: Buttons-HiLite-Right, COLOR: 255 255 255 0, BORDERCOLOR: 255 255 255 0",
        CLEAR, CLEAR,
    ]
    return window("PUSHBUTTON", name, rect, "ENABLED+IMAGE", "PUSHBUTTON+MOUSETRACK",
                  ("Generals", size, 0), "MainButton", colors, enabled, disabled, hilite, 8)


def panel_children():
    kids = []
    kids.append(label("LabelTitle", (152, 21, 450, 54), size=20, template="Title", font="Generals"))
    kids.append(label("LabelVersion", (152, 56, 635, 80)))

    # One row per setting: its name on the left, a button on the right that steps through the
    # values. CommanderMenu.cpp hides the rows a platform does not have.
    rows = ["SimHz", "Renderer", "Upscale", "UiScale", "TextSize"]
    y = 92
    for row in rows:
        kids.append(label(f"Label{row}", (160, y, 384, y + 32)))
        kids.append(button(f"Button{row}", (390, y, 635, y + 32), size=13))
        y += 40

    kids.append(label("LabelSupport", (152, 300, 635, 324), size=14, template="MinorTitle"))
    kids.append(button("ButtonReport", (152, 330, 390, 362), size=13))
    kids.append(button("ButtonMore", (397, 330, 635, 362), size=13))
    kids.append(button("ButtonUpdate", (152, 370, 635, 402), size=13))
    kids.append(label("LabelStatus", (152, 420, 635, 500)))

    kids.append(button("ButtonAccept", (312, 528, 471, 560)))
    kids.append(button("ButtonBack", (476, 528, 635, 560)))
    return kids


def nest(parent_lines, children, indent):
    out = list(parent_lines)
    pad = " " * indent
    for child in children:
        out.append(f"{pad}CHILD")
        out.append(f"{pad}WINDOW")
        out += child
        out.append(f"{pad}END")
    out.append(f"{pad}ENDALLCHILDREN")
    return out


def main():
    root_colors = ("255 255 255 0, ENABLEDBORDER:  255 255 255 0",
                   "255 255 255 0, DISABLEDBORDER: 255 255 255 0",
                   "255 255 255 0, HILITEBORDER:   255 255 255 0")
    root = window("USER", "CommanderMenuParent", (0, 0, 799, 599), "ENABLED", "USER",
                  ("Times New Roman", 14, 0), "[NONE]", root_colors,
                  draw_data("IMAGE: NoImage, COLOR: 2 2 2 126, BORDERCOLOR: 2 2 2 0"),
                  draw_data("IMAGE: NoImage, COLOR: 0 0 64 255, BORDERCOLOR: 0 0 0 255"),
                  draw_data("IMAGE: NoImage, COLOR: 0 0 255 255, BORDERCOLOR: 0 0 0 255"),
                  2, system="CommanderMenuSystem", input_cb="CommanderMenuInput")
    panel_colors = ("255 255 255 255, ENABLEDBORDER:  255 255 255 255",
                    "255 255 255 255, DISABLEDBORDER: 255 255 255 255",
                    "255 255 255 255, HILITEBORDER:   255 255 255 255")
    panel = window("USER", "Panel", (135, 19, 650, 586), "ENABLED", "USER",
                   ("Times New Roman", 14, 0), "[NONE]", panel_colors,
                   draw_data("IMAGE: NoImage, COLOR: 2 2 2 175, BORDERCOLOR: 47 55 168 255"),
                   draw_data("IMAGE: NoImage, COLOR: 64 64 64 255, BORDERCOLOR: 254 254 254 255"),
                   draw_data("IMAGE: NoImage, COLOR: 128 128 255 255, BORDERCOLOR: 254 254 254 255"),
                   4, system="PassMessagesToParentSystem")
    # Children of the panel are indented one level deeper than the panel itself.
    panel_lines = nest(panel, [[("    " + l) for l in kid] for kid in panel_children()], 4)
    lines = [
        "FILE_VERSION = 2;",
        "STARTLAYOUTBLOCK",
        "  LAYOUTINIT = CommanderMenuInit;",
        "  LAYOUTUPDATE = CommanderMenuUpdate;",
        "  LAYOUTSHUTDOWN = CommanderMenuShutdown;",
        "ENDLAYOUTBLOCK",
        "WINDOW",
    ]
    lines += nest(root, [panel_lines], 2)
    lines.append("END")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="\r\n") as f:
        f.write("\n".join(lines) + "\n")
    print(OUT)


if __name__ == "__main__":
    main()
