import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SRC = ROOT / 'app/src/main/java/app/remotessh'
FILES = ['ImageDialog.java', 'MessageImageTile.java', 'MessageBodyView.java', 'SftpDownloadDialog.java']

class MediaResources(unittest.TestCase):
    def test_resources_and_format_arguments_match(self):
        def load(locale):
            elements = ET.parse(RES / locale / 'strings-media-i18n.xml').getroot().findall('string')
            entries = {x.attrib['name']: x.text or '' for x in elements}
            self.assertEqual(len(entries), len(elements))
            return entries
        zh, en = load('values'), load('values-en')
        self.assertEqual(set(zh), set(en))
        for key in zh:
            with self.subTest(key=key):
                self.assertTrue(zh[key] and en[key])
                self.assertEqual(sorted(re.findall(r'%\d+\$[a-z]', zh[key])), sorted(re.findall(r'%\d+\$[a-z]', en[key])))
        used = set()
        for name in FILES:
            source = (SRC / name).read_text()
            used.update(re.findall(r'R\.string\.(media_\w+)', source))
            self.assertNotRegex(source, r'"[^"\n]*[\u4e00-\u9fff][^"\n]*"')
        self.assertEqual(used, set(zh))
    def test_production_code_parser_preserves_remote_text(self):
        source = (SRC / 'MessageBodyView.java').read_text()
        block = source[source.index('  static final class Block {'):source.index('  private final int fg')]
        parser = source[source.index('  static List<Block> blocks('):source.index('  private static List<Block> contentBlocks(')]
        java = '''import java.util.*; import java.util.regex.*;
class TableMarkup { static class Table { String raw; } }
public class Probe {
''' + block + parser + r'''
public static void main(String[] args) {
  String code="echo '中文 /tmp/a %s'\n";
  List<Block> parts=blocks("```sh\n"+code+"```\n```\n第二块\n```");
  if(parts.size()!=2 || !parts.get(0).language.equals("sh") || !parts.get(0).text.equals(code)
      || !parts.get(1).language.isEmpty() || !parts.get(1).text.equals("第二块\n"))
    throw new AssertionError("remote content changed");
}
}'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'Probe.java'
            path.write_text(java)
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, str(path)], check=True)
            subprocess.run(['java', '-cp', directory, 'Probe'], check=True)

    def test_data_and_safety_wiring_remains_intact(self):
        body = (SRC / 'MessageBodyView.java').read_text()
        self.assertIn('copy.accept(block.text)', body)
        self.assertIn(': block.language', body)
        image = (SRC / 'ImageDialog.java').read_text()
        self.assertIn('save.accept(currentSource())', image)
        tile = (SRC / 'MessageImageTile.java').read_text()
        self.assertIn('openImage.accept(source)', tile)
        download = (SRC / 'SftpDownloadDialog.java').read_text()
        for item in ['app.readMedia(path, counting', 'openInputStream(target)', 'openOutputStream(target, "wt")', 'backup = null', 'cancelled.set(true)']:
            self.assertIn(item, download)

if __name__ == '__main__':
    unittest.main()
