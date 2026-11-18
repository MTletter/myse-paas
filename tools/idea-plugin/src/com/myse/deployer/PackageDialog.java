package com.myse.deployer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布包选择对话框：复选框多选 + 「仅校验」开关。
 *
 * <p>用 {@link DialogWrapper} 而非 {@code Messages.showChooseDialog}：
 * 后者只能单选，而发布时经常要一次发多个包（如 coordinator + home）。</p>
 */
public class PackageDialog extends DialogWrapper {

    private final Map<String, JCheckBox> checkBoxes = new LinkedHashMap<>();
    private final JCheckBox checkOnlyBox = new JCheckBox("仅做本地编译校验（不上传、不安装）");

    public PackageDialog(@Nullable Project project) {
        super(project, true);
        setTitle("发布 mySE 代码包");
        setOKButtonText("发布");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (Map.Entry<String, String> e : DeployPackages.PACKAGES.entrySet()) {
            JCheckBox box = new JCheckBox(e.getKey());
            box.setToolTipText(e.getValue());
            box.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            checkBoxes.put(e.getKey(), box);

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            row.add(box, BorderLayout.WEST);
            JLabel desc = new JLabel(e.getValue());
            desc.setEnabled(false);
            java.awt.Font f = desc.getFont();
            desc.setFont(f.deriveFont(f.getSize() - 1f));
            row.add(desc, BorderLayout.CENTER);
            list.add(row);
        }
        list.add(Box.createVerticalStrut(6));

        JPanel tip = new JPanel(new BorderLayout());
        tip.add(new JLabel("勾选要发布的包（可多选），未勾选的包不会被上传。"), BorderLayout.NORTH);
        tip.add(list, BorderLayout.CENTER);
        tip.add(checkOnlyBox, BorderLayout.SOUTH);
        tip.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tip.setPreferredSize(new Dimension(680, 260));

        panel.add(tip, BorderLayout.CENTER);
        return panel;
    }

    /** 选中的包名（按 PACKAGES 顺序） */
    public List<String> getSelectedPackages() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> e : checkBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /** 是否只做本地编译校验 */
    public boolean isCheckOnly() {
        return checkOnlyBox.isSelected();
    }
}
