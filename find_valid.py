import urllib.request, json, re, sys

def get(url):
    return urllib.request.urlopen(url, timeout=30).read().decode('utf-8', 'replace')

tree = json.loads(get("https://api.github.com/repos/mezz/JustEnoughItems/git/trees/1.21.1?recursive=1"))
targets = [i['path'] for i in tree['tree'] if i['path'].endswith('.java')]
hits = []
for p in targets:
    try:
        t = get("https://raw.githubusercontent.com/mezz/JustEnoughItems/1.21.1/" + p.replace(' ', '%20'))
        if 'Received invalid gui properties' in t or 'isValid' in t and 'IGuiProperties' in t:
            hits.append(p)
            for line in t.splitlines():
                if 'invalid gui properties' in line or ('isValid' in line and 'private' in line) or 'MathUtil' in line:
                    print(p, '::', line.strip()[:130])
    except Exception as e:
        pass
print('FILES WITH MARKER:', hits)
