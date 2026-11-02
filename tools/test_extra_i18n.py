"""Resource parity and preservation checks for peripheral dialogs and SFTP."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SRC = ROOT / 'app/src/main/java/app/remotessh'
FILES = ('PendingQueueDialog.java', 'PromptTemplateDialog.java',
         'AttachmentController.java', 'SftpBrowser.java')

def resources(locale):
    root = ET.parse(RES / locale / 'strings-extra.xml').getroot()
    pairs = [(e.attrib['name'], ''.join(e.itertext())) for e in root]
    assert len(pairs) == len(dict(pairs)), 'duplicate resource name'
    return dict(pairs)

def main():
    zh, en = resources('values'), resources('values-en')
    assert zh.keys() == en.keys()
    refs = set()
    for name in FILES:
        source = (SRC / name).read_text()
        assert not re.search(r'[\u4e00-\u9fff]', source), name
        refs.update(re.findall(r'R\.string\.(extra_\w+)', source))
    assert refs == zh.keys(), (refs - zh.keys(), zh.keys() - refs)
    for key in zh:
        fmt = lambda s: sorted(re.findall(r'%\d+\$[ds]', s))
        assert fmt(zh[key]) == fmt(en[key]), key
        assert zh[key] and en[key], key
    attachment = (SRC / 'AttachmentController.java').read_text()
    for literal in ('data:image/', ';base64', 'https://'):
        assert '"' + literal + '"' in attachment
    browser = (SRC / 'SftpBrowser.java').read_text()
    for literal in ('channel is not opened', 'no more sessions'):
        assert 'message.contains("' + literal + '")' in browser
    assert 'ClipData.newPlainText(UiText.get(R.string.extra_sftp_clip_label),full)' in browser
    prompt = (SRC / 'PromptTemplateDialog.java').read_text()
    assert 'draft.setText(t.text)' in prompt and 'setMessage(t.title)' in prompt
    assert 'item.attachments.preview(item.text)' in (SRC / 'PendingQueueDialog.java').read_text()
    print('PASS extra localization: resource parity, format arguments, and preserved data/protocol paths')

if __name__ == '__main__':
    main()
