# Genera todos los iconos de Temvra a partir de los SVG de resources/:
#   temvra-icono.svg  icono completo (fondo y símbolo) en un cuadrado de 512
#   icon-fg.svg       solo el símbolo (lienzo de 1024 = el cuadrado del icono)
#   icon-bg.svg       solo el fondo
# Escribe los iconos de Android (adaptativo, monocromo, antiguo y redondo), el de las notificaciones, las pantallas
# de inicio (splash.png) y resources/icon-512.png (app de PC). Necesita rsvg-convert (librsvg), Pillow y numpy.
# Uso: python3 scripts/make-icons.py
import os, subprocess, tempfile, io, glob
from PIL import Image, ImageDraw

here = os.path.dirname(os.path.abspath(__file__)); repo = os.path.dirname(here)
RS = os.path.join(repo, 'resources'); R = os.path.join(repo, 'android', 'app', 'src', 'main', 'res')

def svg(src, size):
    """Dibuja un SVG (ruta o texto) a size×size con transparencia."""
    tmp = None
    if src.lstrip().startswith('<'):
        tmp = tempfile.NamedTemporaryFile('w', suffix='.svg', delete=False); tmp.write(src); tmp.close(); src = tmp.name
    try:
        png = subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), src], check=True, capture_output=True).stdout
    finally:
        if tmp: os.unlink(tmp.name)
    return Image.open(io.BytesIO(png)).convert('RGBA')

def mask(size, kind):
    """Máscara con antialias: 'round' círculo, 'squircle' cuadrado redondeado como el de la ficha de Play."""
    k = 4; m = Image.new('L', (size * k, size * k), 0); d = ImageDraw.Draw(m)
    if kind == 'round': d.ellipse([0, 0, size * k - 1, size * k - 1], fill=255)
    else: d.rounded_rectangle([0, 0, size * k - 1, size * k - 1], radius=int(size * k * .225), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def shaped(img, kind):
    out = img.copy(); a = Image.composite(img.getchannel('A'), Image.new('L', img.size, 0), mask(img.size[0], kind)); out.putalpha(a); return out

def centered(img, canvas):
    out = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0)); o = (canvas - img.size[0]) // 2; out.alpha_composite(img, (o, o)); return out

FULL = os.path.join(RS, 'temvra-icono.svg'); FG = os.path.join(RS, 'icon-fg.svg')
# Fondo del icono adaptativo: el degradado de icon-bg.svg ocupa los 72 dp visibles y se alarga hasta los 108 dp de la capa
bg_src = open(os.path.join(RS, 'icon-bg.svg'), encoding='utf-8').read()
BG = (bg_src.replace('viewBox="0 0 1024 1024"', 'viewBox="-256 -256 1536 1536"')
      .replace('x1="0" y1="0" x2="1" y2="1"', 'gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1024" y2="1024"')
      .replace('<rect width="1024" height="1024"', '<rect x="-256" y="-256" width="1536" height="1536"'))
assert BG != bg_src and 'userSpaceOnUse' in BG, 'icon-bg.svg ha cambiado: revisa el reemplazo del degradado'
# Silueta blanca del símbolo (notificaciones e icono monocromo): reloj de arena, rayo corto y punto; la mitad de abajo a medias
SIL = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}">'
       '<path d="M176 148 H336 L256 246 Z" fill="#fff" stroke="#fff" stroke-width="28" stroke-linejoin="round"/>'
       '<g opacity=".5"><path d="M256 266 L336 364 H176 Z" fill="#fff" stroke="#fff" stroke-width="28" stroke-linejoin="round"/></g>'
       '<rect x="104" y="250" width="304" height="12" rx="6" fill="#fff"/><circle cx="256" cy="256" r="16" fill="#fff"/></svg>')

dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for k, f in dens.items():
    p = f'{R}/mipmap-{k}'; os.makedirs(p, exist_ok=True)
    s = int(48 * f); full = svg(FULL, s)
    shaped(full, 'squircle').save(f'{p}/ic_launcher.png')
    shaped(full, 'round').save(f'{p}/ic_launcher_round.png')
    big, vis = int(108 * f), int(72 * f)
    centered(svg(FG, vis), big).save(f'{p}/ic_launcher_foreground.png')
    svg(BG, big).save(f'{p}/ic_launcher_background.png')
    centered(svg(SIL.format(vb='0 0 512 512'), vis), big).save(f'{p}/ic_launcher_monochrome.png')
    q = f'{R}/drawable-{k}'; os.makedirs(q, exist_ok=True)
    svg(SIL.format(vb='96 96 320 320'), int(24 * f)).save(f'{q}/ic_stat_temvra.png')

open(f'{R}/values/ic_launcher_background.xml', 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#5B4DF0</color>\n</resources>\n')
logo = shaped(svg(FULL, 1024), 'squircle')
for fpath in glob.glob(f'{R}/drawable*/splash.png'):
    w, h = Image.open(fpath).size; sp = Image.new('RGBA', (w, h), (17, 19, 26, 255)); s = int(min(w, h) * .32)
    sp.alpha_composite(logo.resize((s, s), Image.LANCZOS), ((w - s) // 2, (h - s) // 2)); sp.convert('RGB').save(fpath)
# PC (Windows, Linux y bandeja): cuadrado redondeado con las esquinas transparentes
logo.resize((512, 512), Image.LANCZOS).save(f'{RS}/icon-512.png')
print('Iconos generados')
