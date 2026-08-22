from pathlib import Path
from PIL import Image

source = Path('/home/ubuntu/upload/61f49ef0-9e5b-11f1-991c-bd0bf60952cb.png')
root = Path('/home/ubuntu/work/mupen64plus-ae-rollback/app/src/main/res')
im = Image.open(source).convert('RGBA')
for density, size in {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}.items():
    out = im.resize((size, size), Image.Resampling.LANCZOS)
    directory = root / f'mipmap-{density}'
    directory.mkdir(parents=True, exist_ok=True)
    out.save(directory / 'ic_launcher.png', 'PNG', optimize=True)
    out.save(directory / 'ic_launcher_round.png', 'PNG', optimize=True)
    foreground = im.resize((size * 3 // 2, size * 3 // 2), Image.Resampling.LANCZOS)
    foreground.save(directory / 'ic_launcher_foreground.png', 'PNG', optimize=True)
print('Generated launcher icons from the supplied controller image.')
