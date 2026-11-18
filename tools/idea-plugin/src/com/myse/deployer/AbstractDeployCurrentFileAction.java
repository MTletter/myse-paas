package com.myse.deployer;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Collections;

/**
 * 「发布/校验当前文件所在包」的公共实现。
 *
 * <p>最常用的场景：改完某个 MyseXX.java，直接在编辑器里右键 → 发布当前包，
 * 自动按文件路径推断出包名并调用 {@code tools/deploy.py}，不必去勾选对话框。</p>
 *
 * <p>注意：IDEA 实例化 Action 只能用<b>无参构造器</b>，所以「发布」与「校验」
 * 必须是两个具体子类（见 {@link DeployCurrentFileAction} / {@link DeployCurrentFileCheckAction}），
 * 不能靠构造参数区分。</p>
 */
public abstract class AbstractDeployCurrentFileAction extends AnAction {

    /** true = 只做本地编译校验，不上传不安装 */
    protected abstract boolean isCheckOnly();

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }
        VirtualFile file = CurrentFileUtil.currentFile(e);
        if (file == null) {
            DeployRunner.showNotification(
                    isCheckOnly() ? "校验失败" : "发布失败",
                    "找不到当前文件，请先在编辑器里打开要发布的源码。",
                    NotificationType.WARNING);
            return;
        }
        String path = file.getPath();
        String pkg = PackageResolver.guess(path);
        if (pkg == null) {
            DeployRunner.showNotification(
                    isCheckOnly() ? "校验失败" : "发布失败",
                    "无法识别该文件属于哪个代码包：\n" + path
                            + "\n（只支持 src/codePackages/myse 下的源码）",
                    NotificationType.WARNING);
            return;
        }
        DeployRunner.run(project, Collections.singletonList(pkg), isCheckOnly());
    }

    @Override
    public void update(AnActionEvent e) {
        VirtualFile file = CurrentFileUtil.currentFile(e);
        String pkg = file == null ? null : PackageResolver.guess(file.getPath());
        boolean visible = e.getData(CommonDataKeys.PROJECT) != null && pkg != null;
        e.getPresentation().setEnabledAndVisible(visible);
        if (visible) {
            String jp = PackageResolver.javaPackageOf(pkg);
            e.getPresentation().setText(
                    (isCheckOnly() ? "校验当前包（" : "发布当前包（") + pkg + (jp == null ? "" : " / " + jp) + "）");
        }
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
