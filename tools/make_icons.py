#!/usr/bin/env python3
"""Generates the static PNG icons of LinuxDesk.

Every distro, desktop and app category gets a real icon: a rounded square
in its brand colour with a white monogram.  No emoji anywhere in the UI.
Run:  python3 tools/make_icons.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                   "res", "drawable-nodpi")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SIZE = 256

# name -> (monogram, background colour)
ICONS = {
    # linux distributions
    "ic_d_ubuntu": ("U", "#E95420"), "ic_d_debian": ("D", "#A81C4E"),
    "ic_d_kali": ("K", "#5A4FCF"), "ic_d_fedora": ("F", "#294172"),
    "ic_d_arch": ("A", "#1793D1"), "ic_d_manjaro": ("M", "#35BF5C"),
    "ic_d_alpine": ("Al", "#0D597F"), "ic_d_void": ("V", "#478061"),
    "ic_d_suse": ("S", "#73BA25"), "ic_d_linux": ("Lx", "#333333"),
    # desktop styles
    "ic_de_xfce": ("Xf", "#1F6FB2"), "ic_de_lxqt": ("LQ", "#019DBD"),
    "ic_de_mate": ("Ma", "#7FA847"), "ic_de_kde": ("K", "#1D99F3"),
    "ic_de_lxde": ("LX", "#5B7C99"), "ic_de_gnome": ("Gn", "#4A86CF"),
    "ic_de_cinnamon": ("Ci", "#C95B12"), "ic_de_openbox": ("Ob", "#444444"),
    "ic_de_i3": ("i3", "#222222"), "ic_de_awesome": ("aw", "#333333"),
    "ic_de_cli": (">_", "#1B5E20"),
    # store categories
    "ic_cat_modded": ("Mo", "#4E7A1E"), "ic_cat_ide": ("</>", "#283593"),
    "ic_cat_agent": ("Ai", "#6A1B9A"), "ic_cat_browser": ("Www", "#1565C0"),
    "ic_cat_media": ("Pl", "#AD1457"), "ic_cat_font": ("Aa", "#4E342E"),
    "ic_cat_office": ("Doc", "#2E7D32"), "ic_cat_dev": ("Dev", "#37474F"),
    "ic_cat_game": ("G", "#6D4C00"), "ic_cat_system": ("Sys", "#455A64"),
    "ic_cat_theme": ("Th", "#8E24AA"), "ic_cat_addon": ("+", "#00796B"),
    "ic_cat_terminal": (">_", "#212121"), "ic_cat_store": ("St", "#B26A1B"),
    # well known apps
    "ic_app_firefox": ("Fx", "#E66000"), "ic_app_chromium": ("Cr", "#4285F4"),
    "ic_app_vscode": ("VS", "#007ACC"), "ic_app_vim": ("Nv", "#019833"),
    "ic_app_vlc": ("VLC", "#FF7900"), "ic_app_mpv": ("mpv", "#4A148C"),
    "ic_app_gimp": ("Gp", "#5C5543"), "ic_app_libre": ("LO", "#18A303"),
    "ic_app_opencode": ("Oc", "#111111"), "ic_app_claude": ("Cl", "#D97757"),
    "ic_app_gemini": ("Gm", "#1C7DFF"), "ic_app_python": ("Py", "#3776AB"),
    "ic_app_node": ("Nd", "#339933"), "ic_app_git": ("Gt", "#F05033"),
    # more modded editions and apps (1.3.0)
    "ic_mod_mahesh": ("MD", "#A81C4E"), "ic_mod_brazil": ("BR", "#009739"),
    "ic_mod_optimized": ("OP", "#E95420"), "ic_mod_studio": ("ST", "#7B2D8B"),
    "ic_app_brave": ("Br", "#FB542B"), "ic_app_cursor": ("Cu", "#111111"),
    "ic_app_dotnet": (".N", "#512BD4"), "ic_app_angular": ("Ng", "#DD0031"),
    "ic_mod_arch": ("Ar", "#1793D1"), "ic_mod_fedora": ("Fe", "#294172"),
    "ic_mod_games": ("GM", "#8E24AA"), "ic_mod_kids": ("KD", "#F9A825"),
}

def draw(name, monogram, colour):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([8, 8, SIZE - 8, SIZE - 8], radius=56, fill=colour)
    size = 118 if len(monogram) <= 2 else 84
    font = ImageFont.truetype(FONT, size)
    box = d.textbbox((0, 0), monogram, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((SIZE - w) / 2 - box[0], (SIZE - h) / 2 - box[1]),
           monogram, font=font, fill="white")
    img.save(os.path.join(OUT, name + ".png"))


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (monogram, colour) in ICONS.items():
        draw(name, monogram, colour)
    print("wrote", len(ICONS), "icons into", OUT)


if __name__ == "__main__":
    main()
