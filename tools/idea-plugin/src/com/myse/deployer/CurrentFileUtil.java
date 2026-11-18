package com.myse.deployer;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * 取「当前正在编辑的文件」。
 *
 * <p>优先用编辑器关联的文档（编辑器光标所在文件，最准），
 * 没有编辑器时退回 {@code CommonDataKeys.VIRTUAL_FILE}（Project 面板选中项）。</p>
 */
public final class CurrentFileUtil {

    public static VirtualFile currentFile(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            try {
                VirtualFile f = FileDocumentManager.getInstance().getFile(editor.getDocument());
                if (f != null) {
                    return f;
                }
            } catch (Exception ignored) {
                // 取不到就退回 VIRTUAL_FILE
            }
        }
        return e.getData(CommonDataKeys.VIRTUAL_FILE);
    }

    private CurrentFileUtil() {
    }
}
