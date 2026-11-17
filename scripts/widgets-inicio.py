#!/usr/bin/env python3
"""Recorta de la pantalla de inicio del móvil los widgets de Temvra, tal como los pinta el lanzador real.

Uso: python3 scripts/widgets-inicio.py <carpeta de salida>

Antes hay que dejar el móvil en la página de inicio donde están los widgets. La pantalla entera no se guarda:
solo el recuadro de cada widget, así que la carpeta de salida debe estar fuera del repositorio.
Las posiciones salen de la jerarquía de vistas del lanzador (`dumpsys activity`, que no lleva textos);
`uiautomator dump` no sirve aquí porque el inicio de ColorOS nunca queda en reposo. Necesita Pillow.
"""
import glob, io, os, re, subprocess, sys
from PIL import Image

ADB = os.path.expanduser('~/Android/Sdk/platform-tools/adb')
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if len(sys.argv) < 2:
    sys.exit(__doc__)
OUT = sys.argv[1]


def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True).stdout


# Los ids de nuestros layouts de widget: así se reconocen entre los widgets de otras apps
IDS = set()
for f in glob.glob(os.path.join(RAIZ, 'android/app/src/main/res/layout/widget_*.xml')):
    IDS.update(re.findall(r'@\+id/(\w+)', open(f, encoding='utf8').read()))

inicio = sh('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-c', 'android.intent.category.HOME',
            '-a', 'android.intent.action.MAIN').decode().strip().split('\n')[-1].split('/')[0]


def delante():
    m = re.search(r'mCurrentFocus=.*', sh('shell', 'dumpsys', 'window').decode('utf8', 'replace'))
    return bool(m and inicio + '/' in m.group(0))


if not delante():
    sys.exit('La pantalla de inicio no está delante: no capturo nada.')

raw = sh('shell', 'dumpsys', 'activity', inicio).decode('utf8', 'replace')
i = raw.find('View Hierarchy:')
if i < 0:
    sys.exit('El lanzador no da su jerarquía de vistas.')
# «    clase{hash flags flags izq,arr-der,aba #id app:id/nombre …}»; la sangría da el árbol
rx = re.compile(r'^(\s*)([\w.$]+)\{[0-9a-f]+ (\S+) \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?: #[0-9a-f]+)?(?: \w[\w.]*:\w+/(\w+))?')
nodes, stack = [], []
for ln in raw[i:].split('\n')[1:]:
    m = rx.match(ln)
    if not m:
        if nodes and ln.strip() and not ln.startswith(' '):
            break
        continue
    n = dict(ind=len(m.group(1)), cls=m.group(2), vis=m.group(3)[0], l=int(m.group(4)), t=int(m.group(5)),
             r=int(m.group(6)), b=int(m.group(7)), id=m.group(8) or '', kids=[], par=None)
    while stack and stack[-1]['ind'] >= n['ind']:
        stack.pop()
    if stack:
        n['par'] = stack[-1]
        stack[-1]['kids'].append(n)
    stack.append(n)
    nodes.append(n)
del raw


def sub(n):
    yield n
    for k in n['kids']:
        yield from sub(k)


dens = 3.0
m = re.search(r'(\d+)\s*$', sh('shell', 'wm', 'density').decode().strip().split('\n')[-1])
if m:
    dens = int(m.group(1)) / 160.0

ws = []
for h in nodes:
    # El anfitrión del widget en la rejilla, no los de cada fila de una lista
    if not re.search(r'AppWidgetHostView$', h['cls']) or not h['par'] or 'ShortcutAndWidgetContainer' not in h['par']['cls']:
        continue
    mine = sorted({x['id'] for x in sub(h) if x['id'] in IDS})
    if len(mine) < 2:
        continue
    x = y = 0
    a, pag, total = h, None, None
    while a:  # x dentro de su página; y sumando hasta la raíz (las páginas van una al lado de otra)
        if pag is None and 'CellLayout' in a['cls']:
            her = [k for k in a['par']['kids'] if 'CellLayout' in k['cls']] if a['par'] else [a]
            pag, total = her.index(a), len(her)
        elif pag is None:
            x += a['l']
        y += a['t']
        a = a['par']
    ws.append((h, mine, x, y, pag, total))
if not ws:
    sys.exit('No hay widgets de Temvra en el inicio.')

img = Image.open(io.BytesIO(sh('exec-out', 'screencap', '-p'))).convert('RGB')
if not delante():
    sys.exit('La pantalla cambió durante la captura: no guardo nada.')
os.makedirs(OUT, exist_ok=True)
for k, (h, mine, x, y, pag, total) in enumerate(ws, 1):
    w, alto = h['r'] - h['l'], h['b'] - h['t']
    cont = next((c for c in h['kids'] if c['id'] != 'widget_name'), None)
    nombre = os.path.join(OUT, f'widget-{k}-{mine[-1].split("_")[0]}.png')
    img.crop((max(0, x - 6), max(0, y - 6), min(img.width, x + w + 6), min(img.height, y + alto + 6))).save(nombre)
    print(f'widget {k}: página {pag + 1} de {total} · hueco {w / dens:.0f}×{alto / dens:.0f} dp', end='')
    if cont:  # el lanzador de OPPO monta el widget más grande y lo encoge: los dp que recibe Widgets.java son los del contenido
        cw, ch = cont['r'] - cont['l'], cont['b'] - cont['t']
        print(f' · contenido {cw / dens:.0f}×{ch / dens:.0f} dp', end='')
    print(f' → {nombre}')
    for v in sub(h):
        if v['id'] in IDS and v['vis'] == 'V' and re.search(r'Text|Chronometer', v['cls']) and v['r'] > v['l']:
            print(f"   {v['id']:<14} {(v['r'] - v['l']) / dens:6.1f}×{(v['b'] - v['t']) / dens:5.1f} dp")
print('Mira los recortes: si no enseñan el widget es que el móvil estaba en otra página del inicio.')
