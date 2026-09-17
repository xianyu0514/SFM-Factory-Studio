"""Validate a bundled production JAR before uploading (Python standard library only)."""
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('jar', type=Path)
parser.add_argument('--minecraft', required=True, choices=['1.20.1', '1.21.1'])
parser.add_argument('--version', required=True)
args = parser.parse_args()
forge = args.minecraft == '1.20.1'
with zipfile.ZipFile(args.jar) as jar:
    names = jar.namelist()
    assert len(names) == len(set(names)), 'Duplicate ZIP entries'
    assert jar.testzip() is None, 'Corrupted ZIP entry'
    meta = jar.read('META-INF/mods.toml' if forge else 'META-INF/neoforge.mods.toml').decode()
    assert f'version="{args.version}"' in meta, 'Wrong mod version'
    assert 'modId="sfmfactorystudio"' in meta and 'modId="sfm"' in meta
    assert '${' not in meta, 'Unexpanded metadata'
    assert args.jar.name == f'SFM-Factory-Studio-{args.minecraft}-{args.version}.jar'
    for suffix in ['client/blocks/SfmlCodeEditor.class', 'client/blocks/BlockEditorScreen.class',
                   'client/blocks/model/CodeEditorLayout.class', 'client/blocks/model/CodePaneLayout.class']:
        assert 'io/github/xianynomial/sfmfactorystudio/' + suffix in names, suffix
    assert not any(n.startswith(('net/minecraft/', 'ca/teamdman/')) for n in names), 'Bundled game/SFM classes'
    for name in names:
        if name.endswith('.class'):
            data = jar.read(name)
            assert data[:4] == bytes.fromhex('cafebabe'), name
            assert int.from_bytes(data[6:8], 'big') <= (61 if forge else 65), name
            if forge:
                assert b'me/towdium/pinin' not in data and b'me.towdium.pinin' not in data, name
    zh = json.loads(jar.read('assets/sfmfactorystudio/lang/zh_cn.json'))
    en = json.loads(jar.read('assets/sfmfactorystudio/lang/en_us.json'))
    assert zh.keys() == en.keys(), 'Translation key mismatch'
    assert not any(re.search(r'[\u4e00-\u9fff]', text) for text in en.values()), 'Chinese in English translations'
    assert all(zh[k].count('%s') == en[k].count('%s') for k in en), 'Placeholder mismatch'
    mixins = json.loads(jar.read('sfmfactorystudio.mixins.json'))
    for name in mixins.get('mixins', []) + mixins.get('client', []):
        assert (mixins['package'] + '.' + name).replace('.', '/') + '.class' in names
    if mixins.get('refmap'):
        assert json.loads(jar.read(mixins['refmap'])).get('mappings'), 'Missing declared mixin mappings'
    if forge:
        assert not any(n.startswith('me/towdium/pinin') for n in names)
        assert jar.read('sfmstudio/pinyin/PinIn.class')
        assert jar.read('sfmstudio/pinyin/data.txt')
        assert 'MixinConfigs: sfmfactorystudio.mixins.json' in jar.read('META-INF/MANIFEST.MF').decode()
    else:
        deps = json.loads(jar.read('META-INF/jarjar/metadata.json'))['jars']
        pinin = [dep for dep in deps if dep['identifier']['artifact'] == 'PinIn']
        assert len(pinin) == 1, 'Missing bundled PinIn'
        with zipfile.ZipFile(io.BytesIO(jar.read(pinin[0]['path']))) as nested:
            assert nested.testzip() is None
            assert nested.read('me/towdium/pinin/PinIn.class')
            assert nested.read('me/towdium/pinin/data.txt')
print(f'PASS {args.jar.name}: metadata, bytecode, translations, dependencies and ZIP integrity')
print(hashlib.sha256(args.jar.read_bytes()).hexdigest())
