#!/usr/bin/env python3
"""Structural safety assertions for the SFTP file editor: staged downloads must
never truncate the user's chosen document before the remote read succeeds, and
unsaved drafts must survive page recreation."""
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java/app/remotessh"


def main() -> None:
    viewer = (SRC / "SftpFileActivity.java").read_text()

    # 1) Download is staged into an app-cache temp file; only a fully received
    #    remote file is ever copied into the document the user picked.
    assert 'File.createTempFile("download-",".part",dir)' in viewer, "download must stage to a temp file"
    assert 'content.download(path,out,this::guard);}' in viewer
    assert viewer.index('content.download') < viewer.index('openOutputStream(uri,"wt")'), \
        "remote read must complete before the target document is opened for writing"
    assert 'SftpContent.copy(in,out,SftpContent.TRANSFER_LIMIT,this::guard);}' in viewer, \
        "staged bytes must be copied through the guarded stream"

    # 2) An existing target is backed up and restored when the copy fails.
    assert 'File.createTempFile("backup-",".part",dir)' in viewer, "existing target must be backed up"
    assert "已自动还原原文件" in viewer, "failure path must restore the backup"
    assert "所选文档未被修改" in viewer, "pre-existing target untouched when download itself fails"

    # 3) Drafts: recreation restores the draft; stop persists; save clears;
    #    fresh open offers explicit recovery.
    assert "restoreDraft(drafts.load(DraftStore.key(profile,path)))" in viewer, \
        "recreated page must try to restore its draft"
    assert "drafts.save(DraftStore.key(profile,path)" in viewer, "onStop must persist the draft"
    assert "drafts.clear(DraftStore.key(profile,path));" in viewer, "successful save must clear the draft"
    assert "offerDraftRecovery();" in viewer, "fresh open must offer leftover draft recovery"
    assert "发现未保存的本地草稿" in viewer
    drafts = (SRC / "DraftStore.java").read_text()
    assert "class DraftStore" in drafts and "MAGIC" in drafts and "renameTo" in drafts, \
        "draft store must write atomically"

    # 4) Usage popup: full statistics inline in the header, 刷新 on top,
    #    no detail submenu and no redundant close row.
    main_src = (SRC / "MainActivity.java").read_text()
    assert 'which -> usageInfo(true),' in main_src and 'usageHeaderText()' in main_src, \
        "usage popup shows statistics inline with only a top refresh row"
    assert "usageDetails" not in main_src, "detail submenu removed"

    print("file-editor-safety: 4/4 OK")


if __name__ == "__main__":
    main()
