package com.myse.deployer;

/**
 * 「发布当前包」：按当前编辑的文件自动判断所属代码包，编译校验后上传安装到沙盒。
 */
public class DeployCurrentFileAction extends AbstractDeployCurrentFileAction {

    @Override
    protected boolean isCheckOnly() {
        return false;
    }
}
