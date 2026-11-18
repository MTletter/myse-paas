package com.myse.deployer;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * Tools → 发布 mySE 代码包
 *
 * <p>弹出多选对话框，勾选要发布的包后一次性发布（可多选）。</p>
 */
public class DeployPackagesAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }
        PackageDialog dialog = new PackageDialog(project);
        if (!dialog.showAndGet()) {
            return;
        }
        List<String> selected = dialog.getSelectedPackages();
        if (selected.isEmpty()) {
            return;
        }
        DeployRunner.run(project, selected, dialog.isCheckOnly());
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.PROJECT) != null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
