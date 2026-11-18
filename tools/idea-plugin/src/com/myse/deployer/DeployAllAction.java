package com.myse.deployer;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * 「发布全部包」：按 主包 → general → home → messagecenter → invoice → coordinator 顺序依次发布。
 *
 * <p>发布前弹确认框——全量发布耗时较长（每个包都要编译+上传+安装），
 * 避免误触直接跑一遍。</p>
 */
public class DeployAllAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }
        List<String> all = new ArrayList<>(DeployPackages.PACKAGES.keySet());
        int ok = Messages.showYesNoDialog(project,
                "将依次发布以下 " + all.size() + " 个包到沙盒：\n\n"
                        + String.join("\n", all)
                        + "\n\n每个包都会先做本地编译校验，失败会中止该包。是否继续？",
                "发布全部 mySE 代码包",
                Messages.getQuestionIcon());
        if (ok != Messages.YES) {
            return;
        }
        DeployRunner.run(project, all, false);
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
