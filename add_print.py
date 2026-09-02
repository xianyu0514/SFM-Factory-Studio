import pathlib
p = pathlib.Path('src/test/java/dev/jimu/sfmjimu/test/BlockCodegenTests.java')
t = p.read_text(encoding='utf-8')
needle = 'SfmlTestSupport.assertNoCompileErrors(text);'
dbg = 'System.out.println("ROUNDTRIP>>> " + text + " <<<END");\n        '
if 'ROUNDTRIP' not in t:
    t = t.replace(needle, dbg + needle, 1)
p.write_text(t, encoding='utf-8')
print('added')
